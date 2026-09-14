package com.ecat.integration.ModbusIntegration;

/**
 * This class is part of the Modbus TCP integration module.
 * It utilizes the Modbus4J library to facilitate communication
 * with Modbus devices over TCP/IP or RTU.
 *
 * The `ModbusFactory` from the Modbus4J library is imported to
 * create and manage Modbus master and slave instances, enabling
 * interaction with Modbus-compatible devices.
 *
 * @author coffee
 */
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.msg.ModbusRequest;
import com.serotonin.modbus4j.msg.ReadCoilsRequest;
import com.serotonin.modbus4j.msg.ReadCoilsResponse;
import com.serotonin.modbus4j.msg.ReadDiscreteInputsRequest;
import com.serotonin.modbus4j.msg.ReadDiscreteInputsResponse;
import com.serotonin.modbus4j.msg.ReadExceptionStatusRequest;
import com.serotonin.modbus4j.msg.ReadExceptionStatusResponse;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersRequest;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersResponse;
import com.serotonin.modbus4j.msg.ReadInputRegistersRequest;
import com.serotonin.modbus4j.msg.ReadInputRegistersResponse;
import com.serotonin.modbus4j.msg.ReportSlaveIdRequest;
import com.serotonin.modbus4j.msg.ReportSlaveIdResponse;
import com.serotonin.modbus4j.msg.WriteCoilRequest;
import com.serotonin.modbus4j.msg.WriteCoilResponse;
import com.serotonin.modbus4j.msg.WriteCoilsRequest;
import com.serotonin.modbus4j.msg.WriteCoilsResponse;
import com.serotonin.modbus4j.msg.WriteMaskRegisterRequest;
import com.serotonin.modbus4j.msg.WriteMaskRegisterResponse;
import com.serotonin.modbus4j.msg.WriteRegisterRequest;
import com.serotonin.modbus4j.msg.WriteRegisterResponse;
import com.serotonin.modbus4j.msg.WriteRegistersRequest;
import com.serotonin.modbus4j.msg.WriteRegistersResponse;

import lombok.Getter;

import com.serotonin.modbus4j.msg.ModbusMessage;
import com.serotonin.modbus4j.sero.util.queue.ByteQueue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import com.ecat.core.CommTrace.CommTraceBuffer;
import com.ecat.core.CommTrace.CommTraceTransport;
import com.ecat.core.CommTrace.OwnerLevel;
import com.ecat.core.CommTrace.ResourceOwner;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import com.ecat.integration.SerialIntegration.SerialSource;
import org.slf4j.MDC;

public class ModbusSource {
    private final Log log = LogFactory.getLogger(getClass());
    private final Lock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();
    @Getter
    private final int maxWaiters; // 最大等待请求数
    @Getter
    private final int waitTimeoutMs; // 等待超时时间（毫秒）
    private String currentKey;    // 当前持有锁的key
    private volatile long lockAcquireTime;   // 持锁时刻（幽灵锁收割判据）
    private volatile String lockAcquireThread; // 持锁线程名（幽灵锁收割日志定位）
    // 当前持锁事务的注册 owner（io-resource-owner §5.3 锁）：授予点登记、release 与幽灵锁
    // 收割按同一状态机清除；sendTraced 捕获点以此作 TX/RX 权威归因（owner 在=整组投影，
    // 不与线程 MDC 混搭）。null=无锁路径或 LEGACY 注册——此时捕获点按 frameAttributionOwner()
    // 落源注册兜底（坐标级投影），无类型化注册者才维持 MDC 归因现状。
    private volatile ResourceOwner lockAcquireOwner;
    private final Queue<String> waitQueue = new LinkedList<>(); // 等待队列（保存请求标识）

    /**
     * 幽灵锁收割阈值：currentKey 持续超过该时长即判定持锁事务已死（release 永久缺失），
     * 强制清锁救活源。合法事务的硬超时上限 = requestTimeout×6（boundedReadWaitMs），
     * 秒级~十秒级，5 分钟远超任何合法持锁时长。package-private 非 final 供红测缩短。
     */
    static final long DEFAULT_GHOST_REAP_THRESHOLD_MS = 300_000L;
    private long ghostReapThresholdMs = DEFAULT_GHOST_REAP_THRESHOLD_MS;

    /**
     * 红测注入口：缩短幽灵锁收割阈值（仅同包测试使用；生产用默认 5 分钟）。
     */
    void setGhostReapThresholdMsForTest(long thresholdMs) {
        if (thresholdMs <= 0) {
            throw new IllegalArgumentException("ghostReapThresholdMs must be > 0, got: " + thresholdMs);
        }
        this.ghostReapThresholdMs = thresholdMs;
    }
    // 通讯帧捕获事务配对序号（TX/RX 同 txnId）
    private static final AtomicLong TXN_COUNTER = new AtomicLong(0);

    private ModbusMaster modbusMaster;
    @Getter
    private ModbusInfo modbusInfo;
    // 注册方账本（io-resource-owner §5.3）：ownerKey → ResourceOwner。账本被注册方线程
    // （register/registerForDevice）与摘账线程（closeModbus/宿主 onRemove 收尾）并发读写，
    // ConcurrentHashMap 弱一致迭代保证 getRegisteredOwners 读面不抛 CME 不漏读（对齐 serial
    // W4 账本形态）；消费方（findSourceByOwner/折叠查询/测试）均按 ownerKey 精确匹配，无
    // 插入序依赖，无需保序结构。同键重注册天然去重（一名一票）；LEGACY 字符串身份以
    // legacy 包装入账，与类型化 owner 同账本混存（迁移过渡期常态）。
    private final Map<String, ResourceOwner> registeredOwners = new ConcurrentHashMap<>();
    // modbus IO 旁池（R3 期 4，15 号 §6.4）：阻塞 send（含写+等回音）的专职有界池
    // ecat-modbus-io-N，与调度引擎车道彻底分离——发起段（本源读/写方法调用方，含引擎 lane
    // worker 上的轮询任务体/写闸）提交即返，响应窗的阻塞消耗由旁池线程承接，引擎 worker
    // 不再被逐事务钉死（084500 风暴族根除形态）。池归 ModbusIntegration 生命周期管理
    // （onInit 初始化/onRelease 关停）；无集成上下文（单测/独立运行）由 ModbusIoPool 惰性建默认池。
    private final ExecutorService ioExecutor;
    // 同源待发队列（R3 期 4，提交面串行）：同源（=同连接）的帧 FIFO 逐帧消费、同一时刻至多
    // 占一个旁池线程（单飞 drain，见 dispatchIo）。等待中的帧是纯 Java 对象——排队零线程消耗
    // （区别于「池内等信号量」形态：事务内并行段不放大池占用）。guarded by this。
    private final ArrayDeque<PendingSend> pendingSends = new ArrayDeque<>();
    // 本源单飞标志：true=已有 drain 任务在旁池消费本源队列（dispatch 与 drain 在 this 锁下
    // 交接，无丢失唤醒）。guarded by this。
    private boolean sendDraining = false;
    // 死源显式标志（destroyResources 置位）：isModbusOpen 的死源防线。
    private volatile boolean destroyed;

    protected ModbusSource(ModbusInfo modbusInfo) {
        this(modbusInfo, Const.DEFAULT_MAX_WAITERS, Const.DEFAULT_WAIT_TIMEOUT_MS); // 默认最大等待请求数为1，等待超时时间为Const.WAIT_TIMEOUT_MS
    }

    protected ModbusSource(ModbusInfo modbusInfo, int maxWaiters, int waitTimeoutMs) {
        this(modbusInfo, maxWaiters, waitTimeoutMs, false, false);
    }

    /**
     * 构造函数
     *
     * @param modbusInfo    Modbus 设备信息
     * @param maxWaiters    最大等待请求数
     * @param waitTimeoutMs 等待超时时间（毫秒）
     * @param skipOpen      是否跳过 openModbus()（RTU 新模式跳过，由 initSerialMaster 初始化）
     * @param delegateMode  是否为委托模式（DeviceSpecificModbusSource 使用；send 委托共享源执行，
     *                      本实例的旁池引用不参与其事务路径）
     */
    protected ModbusSource(ModbusInfo modbusInfo, int maxWaiters, int waitTimeoutMs, boolean skipOpen, boolean delegateMode) {
        this.maxWaiters = maxWaiters; // 设置资源最大等待请求数
        this.waitTimeoutMs = waitTimeoutMs; // 设置资源等待超时时间
        this.modbusInfo = modbusInfo;
        this.ioExecutor = ModbusIoPool.executor();
        if (!skipOpen) {
            openModbus();
        }
    }

    /**
     * 通过 ModbusMasterFactory 创建 master 并初始化（用于 RTU 新模式）。
     * 工厂内部跟踪 SerialSource 生命周期。
     *
     * @param serialInfo 串口配置
     * @param serialSource 来自 serial integration 的串口资源
     */
    protected void initSerialMaster(ModbusSerialInfo serialInfo, SerialSource serialSource) {
        try {
            this.modbusMaster = ModbusMasterFactory.createSerialMaster(serialInfo, serialSource);
            modbusMaster.init();
        } catch (ModbusInitException e) {
            log.error( "Failed to initialize RTU master with SerialSource. serialInfo: " + serialInfo.toString(), e);
        }
    }

    /**
     * 通讯帧捕获事务层包装：在能拿到 modbus4j 帧字节的最近点（消息对象 encode）捕获
     * TX（请求编码字节）与 RX（响应编码字节 + 事务耗时 + txnId 配对），失败累计通道错误。
     *
     * <p>说明：RTU 的 CRC 与 TCP 的 MBAP 头由 modbus4j 传输层在 send 内追加/消费，
     * 本层捕获的是 PDU+slaveId 编码字节（协议语义完整）；串口原始字节不经此处——
     * modbus RTU 走 wrapper 直持流，SerialSourcePort 的读写路径不参与，两层无重复捕获。
     */
    private ModbusMessage sendTraced(ModbusRequest request) throws ModbusTransportException {
        final CommTraceTransport transport;
        final String portId;
        if (modbusInfo instanceof ModbusSerialInfo) {
            transport = CommTraceTransport.SERIAL;
            portId = ((ModbusSerialInfo) modbusInfo).getPortName();
        } else if (modbusInfo instanceof ModbusTcpInfo) {
            transport = CommTraceTransport.MODBUS_TCP;
            ModbusTcpInfo tcp = (ModbusTcpInfo) modbusInfo;
            portId = tcp.getIpAddress() + ":" + tcp.getPort();
        } else {
            // 与 ModbusMasterFactory 同口径：未知类型无法建立连接，本方法不可达；抛明示而非猜测
            throw new IllegalStateException("未知的Modbus协议类型: " + modbusInfo.getClass().getName());
        }
        String txnId = "mb-" + TXN_COUNTER.incrementAndGet();
        long startNanos = System.nanoTime();
        // 捕获点权威归因（io-resource-owner §5.3）：帧归属=当前持锁 owner（modbus 事务恒在
        // 锁内发起，同连接多 slave 逐笔归属）；无锁/LEGACY 时落源注册兜底（见
        // frameAttributionOwner——归因次序 owner > MDC > 回填在 CommTraceBuffer 仍成立）。
        // 单次取值冻结 TX/RX 同帧同主（事务硬超时清锁不撕裂同一对的归因）。
        ResourceOwner frameOwner = frameAttributionOwner();
        CommTraceBuffer.instance().tx(transport, portId, encodeMessage(request), txnId, frameOwner);
        try {
            ModbusMessage response = modbusMaster.send(request);
            double durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;
            CommTraceBuffer.instance().rx(transport, portId,
                    response != null ? encodeMessage(response) : new byte[0], txnId, durationMs, frameOwner);
            return response;
        } catch (ModbusTransportException e) {
            CommTraceBuffer.instance().error(transport, portId);
            throw e;
        }
    }

    /**
     * 本帧权威 owner（归因次序：持锁 owner &gt; 源注册兜底 &gt; null 落线程 MDC）。
     *
     * <p>无锁兜底（bug-record-20260913-111500）：裸 dispatchIo 读事务（mgd 轮询形态）
     * 不经源锁，lockAcquireOwner 恒 null，帧归因此前只剩提交线程 MDC——RECONFIGURE
     * 热加载在 REST 线程重新调度轮询后 MDC 快照丢 coordinate，帧变无主。兜底取本源
     * 注册账本首个类型化注册者，投影成<b>坐标级</b> owner：无锁路径本无逐笔身份，
     * 注册者层级无论 ENTRY/DEVICE 一律折到坐标（不引入 MDC 时代不存在的设备维度；
     * 有锁路径的逐笔 DEVICE 归因不受影响）。
     *
     * <p>确定性：账本 ConcurrentHashMap 同键集迭代序确定，多注册者取迭代首位——同连接
     * 共享源的注册者坐标一致（同集成多设备/同集成多模块），coordinate 投影不受选取
     * 影响。账本无类型化注册者（空/全 LEGACY）返回 null，维持 MDC 既有次序（LEGACY
     * 无坐标字段，注入反而压制 MDC 归因，与锁通道同判定）。
     */
    private ResourceOwner frameAttributionOwner() {
        if (lockAcquireOwner != null) {
            return lockAcquireOwner;
        }
        for (ResourceOwner owner : registeredOwners.values()) {
            if (owner.getLevel() != OwnerLevel.LEGACY) {
                return ResourceOwner.integration(owner.getCoordinate());
            }
        }
        return null;
    }

    /** modbus4j 消息 → 编码字节（write 落 ByteQueue 后整体取出）。 */
    static byte[] encodeMessage(ModbusMessage message) {
        try {
            ByteQueue queue = new ByteQueue();
            message.write(queue);
            return queue.popAll();
        } catch (Exception e) {
            // write 只做内存编码；异常仅可能在消息状态非法时出现，如实返回空帧由截断层处理
            return new byte[0];
        }
    }

    // 新增：带slaveId参数的内部方法
    protected CompletableFuture<ReadCoilsResponse> readCoilsWithSlaveId(Integer slaveId, int startAddress, int numberOfBits) {
        return dispatchIo(() -> new ReadCoilsRequest(slaveId, startAddress, numberOfBits),
                "Error reading coils. slaveId: " + slaveId +
                        " (startAddress: " + startAddress + ", numberOfBits: " + numberOfBits +
                        ", modbusInfo=" + modbusInfo + ")");
    }

    protected CompletableFuture<ReadDiscreteInputsResponse> readDiscreteInputsWithSlaveId(Integer slaveId, int startAddress, int numberOfBits) {
        return dispatchIo(() -> new ReadDiscreteInputsRequest(slaveId, startAddress, numberOfBits),
                "Error reading discrete inputs. slaveId: " + slaveId +
                        " (startAddress: " + startAddress + ", numberOfBits: " + numberOfBits +
                        ", modbusInfo=" + modbusInfo + ")");
    }

    protected CompletableFuture<ReadExceptionStatusResponse> readExceptionStatusWithSlaveId(Integer slaveId) {
        return dispatchIo(() -> new ReadExceptionStatusRequest(slaveId),
                "Error reading exception status. slaveId: " + slaveId + ", modbusInfo=" + modbusInfo);
    }

    protected CompletableFuture<ReadHoldingRegistersResponse> readHoldingRegistersWithSlaveId(Integer slaveId, int startAddress, int numberOfRegisters) {
        return dispatchIo(() -> new ReadHoldingRegistersRequest(slaveId, startAddress, numberOfRegisters),
                "Error reading holding registers. slaveId: " + slaveId +
                        " (startAddress: " + startAddress + ", numberOfRegisters: " + numberOfRegisters +
                        ", modbusInfo=" + modbusInfo + ")");
    }

    protected CompletableFuture<ReadInputRegistersResponse> readInputRegistersWithSlaveId(Integer slaveId, int startAddress, int numberOfRegisters) {
        return dispatchIo(() -> new ReadInputRegistersRequest(slaveId, startAddress, numberOfRegisters),
                "Error reading input registers. slaveId: " + slaveId +
                        " (startAddress: " + startAddress + ", numberOfRegisters: " + numberOfRegisters +
                        ", modbusInfo=" + modbusInfo + ")");
    }

    protected CompletableFuture<ReportSlaveIdResponse> reportSlaveIdWithSlaveId(Integer slaveId) {
        return dispatchIo(() -> new ReportSlaveIdRequest(slaveId),
                "Error reporting slave ID. slaveId: " + slaveId + ", modbusInfo=" + modbusInfo);
    }

    // 写系列（writeXxxWithSlaveId）与读系列同经 dispatchIo 派发旁池（R3 期 4）：
    // M8 同 lane 自锁修复（bug-record-20260825-101000）采用的「调用线程直发」形态随车道
    // 依赖一并退役——旁池不是 lane，发起段提交旁池 = O(1)，无同 lane 重入自锁；lane worker
    // 上的写闸 join 的是旁池完成的 future（跨执行器 join，非自等自）。调用方对返回类型/
    // null 语义/日志语义零感知。
    protected CompletableFuture<WriteCoilResponse> writeCoilWithSlaveId(Integer slaveId, int address, boolean value) {
        return dispatchIo(() -> new WriteCoilRequest(slaveId, address, value),
                "Error writing coil. slaveId: " + slaveId +
                        " (address: " + address + ", value: " + value +
                        ", modbusInfo=" + modbusInfo + ")");
    }

    protected CompletableFuture<WriteCoilsResponse> writeCoilsWithSlaveId(Integer slaveId, int startAddress, boolean[] values) {
        return dispatchIo(() -> new WriteCoilsRequest(slaveId, startAddress, values),
                "Error writing coils. slaveId: " + slaveId +
                        " (startAddress: " + startAddress + ", values: " + Arrays.toString(values) +
                        ", modbusInfo=" + modbusInfo + ")");
    }

    protected CompletableFuture<WriteMaskRegisterResponse> writeMaskRegisterWithSlaveId(Integer slaveId, int address, int andMask, int orMask) {
        return dispatchIo(() -> new WriteMaskRegisterRequest(slaveId, address, andMask, orMask),
                "Error writing mask register. slaveId: " + slaveId +
                        " (address: " + address + ", andMask: " + andMask + ", orMask: " + orMask +
                        ", modbusInfo=" + modbusInfo + ")");
    }

    protected CompletableFuture<WriteRegisterResponse> writeRegisterWithSlaveId(Integer slaveId, int address, int value) {
        return dispatchIo(() -> new WriteRegisterRequest(slaveId, address, value),
                "Error writing register. slaveId: " + slaveId +
                        " (address: " + address + ", value: " + value +
                        ", modbusInfo=" + modbusInfo + ")");
    }

    protected CompletableFuture<WriteRegistersResponse> writeRegistersWithSlaveId(Integer slaveId, int startAddress, short[] values) {
        return dispatchIo(() -> new WriteRegistersRequest(slaveId, startAddress, values),
                "Error writing registers. slaveId: " + slaveId +
                        " (startAddress: " + startAddress + ", values: " + Arrays.toString(values) +
                        ", modbusInfo=" + modbusInfo + ")");
    }

    /** 单帧 IO 请求构造器（在旁池线程上执行）：modbus4j 请求构造可抛 ModbusTransportException（非法 slaveId/越界地址）。 */
    @FunctionalInterface
    private interface IoRequestFactory {
        ModbusRequest create() throws ModbusTransportException;
    }

    /**
     * 同源单条待发帧（R3 期 4）：请求工厂 + 错误文案 + 结果 CF + 提交时捕获的 MDC
     * （旁池线程上恢复，per-send 日志归因与旧 supplyAsync 任务粒度一致）。
     */
    private static final class PendingSend {
        final IoRequestFactory requestFactory;
        final String errorLabel;
        final Map<String, String> mdcContext;
        final CompletableFuture<ModbusMessage> result = new CompletableFuture<>();

        PendingSend(IoRequestFactory requestFactory, String errorLabel, Map<String, String> mdcContext) {
            this.requestFactory = requestFactory;
            this.errorLabel = errorLabel;
            this.mdcContext = mdcContext;
        }
    }

    /**
     * 把单次阻塞 master.send（含写+等回音）派发到 modbus IO 旁池执行（R3 期 4，15 号 §6.4）。
     *
     * <p>发起段 O(1)：入本源待发队即返回 CF，阻塞消耗由 {@code ecat-modbus-io-N} 线程承接——
     * 引擎 lane worker 不再被响应窗钉死（发起即返；jstack 断言：ecat-sched-worker 无
     * master.send 栈）。
     *
     * <p><b>同源单飞（提交面串行）</b>：同源（=同 TCP 连接/同 RS485 总线，DeviceSpecific
     * 委托共享源）的帧在本源 FIFO 队列按序消费，同一时刻至多占一个旁池线程——防帧碰撞
     * （modbus4j RtuMaster.sendImpl 无方法级同步，TcpMaster 的 synchronized sendImpl 只是
     * 实现细节，均不得作正确性依赖），且事务内并行段（saimosen allOf 四段读、tyxdgroup
     * thenCombine 双读这类「急切多读」形态）不放大池占用（live 实证：信号量在池内等许可
     * 的形态令 saimosen 单事务占 4 线程、9 源即饱和 16 线程池——本形态为修订定稿）。
     * 跨事务互斥仍由源锁保证（轮询经 acquirePollingBounded 有界等待，预算耗尽才弃轮）。
     *
     * <p>失败表面不变：请求构造/传输异常（ModbusTransportException，构造与 send 同域——
     * 原「写在调用线程构造、读在池内构造」两形态统一）→ CF 完成值 null（调用方 null 语义
     * 不变）；未知 RuntimeException → CF 异常完成如实上抛。旁池饱和拒绝（线程+有界队全满）
     * → 本源全部待发帧 CF 立即异常完成（RejectedExecutionException）——事务级「过期即弃」：
     * 轮询本周期放弃下周期再试 / 写闸记 FAILED，不阻塞发起段。
     *
     * @param requestFactory 单帧请求构造（旁池线程上执行）
     * @param errorLabel     传输异常时的 ERROR 日志文案（含 slaveId/modbusInfo 定位信息）
     * @return 响应 CF：成功=响应对象；传输异常=null；池饱和=RejectedExecutionException 异常完成
     */
    @SuppressWarnings("unchecked")
    private <R extends ModbusMessage> CompletableFuture<R> dispatchIo(IoRequestFactory requestFactory, String errorLabel) {
        PendingSend send = new PendingSend(requestFactory, errorLabel, MDC.getCopyOfContextMap());
        boolean submitDrain = false;
        synchronized (this) {
            pendingSends.addLast(send);
            if (!sendDraining) {
                sendDraining = true;
                submitDrain = true;
            }
        }
        if (submitDrain) {
            submitSendDrain();
        }
        return (CompletableFuture<R>) (CompletableFuture<?>) send.result;
    }

    /**
     * 提交「消费本源待发队列」任务到旁池。池饱和（AbortPolicy）→ 本源全部待发帧立即
     * 异常完成并复位单飞标志（下一事务的首次派发重新尝试提交，源可自愈）。
     */
    private void submitSendDrain() {
        try {
            ioExecutor.execute(this::drainSends);
        } catch (RejectedExecutionException poolSaturated) {
            failAllPendingSends(poolSaturated);
        }
    }

    /**
     * 消费本源待发队列（旁池线程）：逐帧构造请求→sendTraced→完成 CF。持有单飞标志直到
     * 队列空（期间新入队帧被本循环直接消费，不再占新池任务）。每帧恢复其提交时 MDC，
     * CF 的同步依赖（解析/属性更新链）在该帧自己的 MDC 下执行。
     */
    private void drainSends() {
        for (;;) {
            PendingSend send;
            synchronized (this) {
                send = pendingSends.pollFirst();
                if (send == null) {
                    sendDraining = false;
                    return;
                }
            }
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            try {
                if (send.mdcContext != null) {
                    MDC.setContextMap(send.mdcContext);
                }
                send.result.complete(sendTraced(send.requestFactory.create()));
            } catch (ModbusTransportException e) {
                log.error(send.errorLabel, e);
                send.result.complete(null);
            } catch (RuntimeException e) {
                // 未知异常如实异常完成（严格模式：不吞不换 null 掩盖）
                send.result.completeExceptionally(e);
            } finally {
                if (send.mdcContext != null) {
                    if (previousMdc != null) {
                        MDC.setContextMap(previousMdc);
                    } else {
                        MDC.clear();
                    }
                }
            }
        }
    }

    /**
     * 池饱和时失败本源全部待发帧（过期即弃）：逐帧异常完成（RejectedExecutionException），
     * 清队并复位单飞标志（锁内快照、锁外完成，避免 CF 依赖链在持锁下执行）。
     */
    private void failAllPendingSends(RejectedExecutionException cause) {
        List<PendingSend> drained = new ArrayList<>();
        synchronized (this) {
            PendingSend send;
            while ((send = pendingSends.pollFirst()) != null) {
                drained.add(send);
            }
            sendDraining = false;
        }
        for (PendingSend send : drained) {
            log.warn("Modbus IO 旁池饱和，本帧立即失败（过期即弃，轮询下周期再试）: " + send.errorLabel);
            send.result.completeExceptionally(cause);
        }
    }

    public CompletableFuture<ReadCoilsResponse> readCoils(int startAddress, int numberOfBits) {
        return readCoilsWithSlaveId(modbusInfo.getSlaveId(), startAddress, numberOfBits);
    }

    public CompletableFuture<ReadDiscreteInputsResponse> readDiscreteInputs(int startAddress, int numberOfBits) {
        return readDiscreteInputsWithSlaveId(modbusInfo.getSlaveId(), startAddress, numberOfBits);
    }

    public CompletableFuture<ReadExceptionStatusResponse> readExceptionStatus() {
        return readExceptionStatusWithSlaveId(modbusInfo.getSlaveId());
    }

    public CompletableFuture<ReadHoldingRegistersResponse> readHoldingRegisters(int startAddress, int numberOfRegisters) {
        return readHoldingRegistersWithSlaveId(modbusInfo.getSlaveId(), startAddress, numberOfRegisters);
    }

    public CompletableFuture<ReadInputRegistersResponse> readInputRegisters(int startAddress, int numberOfRegisters) {
        return readInputRegistersWithSlaveId(modbusInfo.getSlaveId(), startAddress, numberOfRegisters);
    }

    public CompletableFuture<ReportSlaveIdResponse> reportSlaveId() {
        return reportSlaveIdWithSlaveId(modbusInfo.getSlaveId());
    }

    public CompletableFuture<WriteCoilResponse> writeCoil(int address, boolean value) {
        return writeCoilWithSlaveId(modbusInfo.getSlaveId(), address, value);
    }

    public CompletableFuture<WriteCoilsResponse> writeCoils(int startAddress, boolean[] values) {
        return writeCoilsWithSlaveId(modbusInfo.getSlaveId(), startAddress, values);
    }

    public CompletableFuture<WriteMaskRegisterResponse> writeMaskRegister(int address, int andMask, int orMask) {
        return writeMaskRegisterWithSlaveId(modbusInfo.getSlaveId(), address, andMask, orMask);
    }

    public CompletableFuture<WriteRegisterResponse> writeRegister(int address, int value) {
        return writeRegisterWithSlaveId(modbusInfo.getSlaveId(), address, value);
    }

    public CompletableFuture<WriteRegistersResponse> writeRegisters(int startAddress, short[] values) {
        return writeRegistersWithSlaveId(modbusInfo.getSlaveId(), startAddress, values);
    }

    /**
     * 请求级 I/O 超时（毫秒），透传 {@link ModbusInfo#getRequestTimeoutMs()}：
     * 供消费方派生同步等待读 future 的有界上限（103000，见
     * {@link ModbusTransactionStrategy#boundedReadWaitMs(ModbusSource)}）。
     */
    public int getRequestTimeoutMs() {
        return modbusInfo.getRequestTimeoutMs();
    }

    /**
     * LEGACY 字符串身份注册（迁移过渡期旧签名）：包装 legacy owner 入账，与类型化
     * owner 同账本混存。新代码走 {@link #registerIntegration(ResourceOwner)}。
     *
     * @param identity 注册方字符串身份
     */
    public void registerIntegration(String identity) {
        registerIntegration(ResourceOwner.legacy(identity));
    }

    /**
     * 注册方 owner 入账（io-resource-owner §5.3 账本）：同 ownerKey 重注册天然去重；
     * 摘账走 {@link #closeModbus(ResourceOwner)}，账本摘空才销毁底层资源（引用计数语义
     * 不变，仅记账粒度从字符串升为 owner）。
     *
     * @param owner 注册方 owner（设备/入口/集成层均可）
     */
    public void registerIntegration(ResourceOwner owner) {
        Objects.requireNonNull(owner, "owner");
        registeredOwners.put(owner.ownerKey(), owner);
    }

    /** LEGACY 字符串身份摘账（迁移过渡期旧签名）。 */
    protected void removeIntegration(String identity) {
        removeIntegration(ResourceOwner.legacy(identity));
    }

    /** 注册方 owner 摘账（不销毁资源；生命周期收口走 closeModbus(owner)）。 */
    protected void removeIntegration(ResourceOwner owner) {
        registeredOwners.remove(owner.ownerKey());
    }

    /** 注册方 owner 只读快照（账本查询面；拷贝返回，外部修改不回写）。 */
    List<ResourceOwner> getRegisteredOwners() {
        return new ArrayList<>(registeredOwners.values());
    }

    /**
     * 尝试获取锁，支持等待队列
     * @return 锁标识（成功获取或进入等待），null表示无法获取且超出等待队列容量
     */
    public String acquire() {
        return acquire(waitTimeoutMs, TimeUnit.MILLISECONDS, null);
    }

    /**
     * 带注册 owner 获取锁（io-resource-owner §5.3 锁）：授予点把 owner 登记为
     * {@code lockAcquireOwner}（事务期间 TX/RX 捕获点的权威归因来源），
     * release/幽灵锁收割与 key 同一状态机清除。LEGACY owner 请传 null——LEGACY 无设备
     * 身份字段，注入反而会压制线程 MDC 归因（过渡期行为不得回退）。
     *
     * @param owner 本次事务的注册 owner（null=无主事务，保持现状）
     * @return 锁标识（成功获取或进入等待），null表示无法获取且超出等待队列容量
     */
    public String acquire(ResourceOwner owner) {
        return acquire(waitTimeoutMs, TimeUnit.MILLISECONDS, owner);
    }

    /**
     * 尝试获取锁，支持等待队列和超时
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 锁标识（成功获取/唤醒或进入等待），null表示超时、超出等待队列容量，
     *         或唤醒后锁已被快速路径请求抢占（handed-off race 失败，按未取得总线重试）
     */
    public String acquire(long timeout, TimeUnit unit) {
        return acquire(timeout, unit, null);
    }

    /**
     * 锁获取核心（owner 登记版）：两处授予点（快速路径 / 队头交接）均登记
     * {@code lockAcquireOwner}，与 currentKey 同生共死。
     *
     * @param timeout 超时时间
     * @param unit 时间单位
     * @param owner 本次事务的注册 owner（null=无主事务）
     * @return 锁标识；语义同 {@link #acquire(long, TimeUnit)}
     */
    public String acquire(long timeout, TimeUnit unit, ResourceOwner owner) {
        String requestKey = generateRequestKey(); // 生成唯一请求标识
        lock.lock();
        try {
            reapGhostLockIfStale("acquire");
            if (currentKey == null) {
                // 直接获取锁
                currentKey = requestKey;
                lockAcquireTime = System.currentTimeMillis();
                lockAcquireThread = Thread.currentThread().getName();
                lockAcquireOwner = owner;
                return requestKey;
            } else {
                // 检查等待队列是否未满
                if (waitQueue.size() < maxWaiters) {
                    waitQueue.add(requestKey);
                    boolean isAwoken = false;
                    try {
                        // 等待唤醒或超时
                        isAwoken = condition.await(timeout, unit);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); // 恢复中断状态
                        log.error( "Wait interrupted: " + requestKey + ", modbusInfo: " + modbusInfo.toString(), e);
                        waitQueue.remove(requestKey); // 从队列移除
                        return null;
                    }

                    if (isAwoken) {
                        // 接管锁必须同时满足：自己是队头 且 锁确实空闲（currentKey==null 复查不可省：
                        // release() 发出 signal 之后、等待者重入临界区之前，另一请求可经快速路径
                        // （currentKey==null 分支）抢先持有；若仍执行 currentKey = requestKey 会无声
                        // 覆盖其持有权，两个 key 同时自认持锁，共享连接/RS485 总线上并发收发即帧碰撞）
                        if (currentKey == null && waitQueue.peek() != null && waitQueue.peek().equals(requestKey)) {
                            currentKey = requestKey;
                            lockAcquireTime = System.currentTimeMillis();
                            lockAcquireThread = Thread.currentThread().getName();
                            lockAcquireOwner = owner;
                            waitQueue.poll();
                            return requestKey;
                        }
                        // 队头不是自己（队列变更，skip），或锁已被快速路径抢占：返回 null 前必须摘除
                        // 自身 key。若残留队头即成死 key——此后每次 signal 唤醒的等待者都因队头不匹配
                        // 被错误拒绝并同样泄漏，队列只增不减直至 maxWaiters 名额被尸体耗尽。
                        waitQueue.remove(requestKey);
                        return null;
                    } else {
                        // 超时处理
                        waitQueue.remove(requestKey); // 从队列移除超时请求
                        log.error( "Acquire timeout: " + requestKey + ", modbusInfo: " + modbusInfo.toString());
                        return null;
                    }
                } else {
                    log.warn( "Max waiters exceeded, request rejected: " + requestKey);
                    return null; // 超出最大等待数，直接返回不可用
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /** 轮询真弃轮计数（E2/R3 记账：有界等待预算耗尽才计，禁静默）。 */
    private final AtomicLong lockBusySkipCount = new AtomicLong();
    /** 锁忙放弃日志限频时间戳（锁临界区内读写，ReentrantLock 保证可见性，仅日志用）。 */
    private volatile long lastBusySkipLogAt;
    /** 锁忙放弃日志限频间隔：默认 60s 一条（饱和期 ~30 次/min 的放弃若不限频会刷爆日志）。 */
    static final long BUSY_SKIP_LOG_INTERVAL_MS = 60_000L;

    /** 获取累计真弃轮次数（轮询有界等待预算耗尽的计数，运行时可观测用）。 */
    public long getLockBusySkipCount() {
        return lockBusySkipCount.get();
    }

    /**
     * 轮询锁获取契约（20260913-073600 修复，方案 c：轮询并入 FIFO 有界等待）：无竞争
     * 快路径直达；锁忙时把 FIFO 有界等待移交 IO 旁池线程执行——调用线程（SDK 定时线程）
     * 零 park，轮询重新与写命令同队同公平（deed33d 把轮询踢出等待队列是共享口相位锁定
     * 饥饿的根因）。
     *
     * <p>返回 CF 语义：完成值非 null=锁标识（等待授予发生在旁池线程，事务体随 CF 依赖链
     * 在同一旁池线程执行，MDC 已恢复到提交时快照）；null=本周期真弃轮（预算耗尽/等待
     * 队列满/旁池饱和拒绝），源侧记账。等到了锁的轮次不计入
     * {@link #getLockBusySkipCount()}（观测不说谎）。
     *
     * @param budgetMs 锁等待预算（毫秒，须 &gt; 0；SDK 侧按轮询周期 clamp）
     * @return 锁标识 CF；null 完成=本周期弃轮
     */
    public CompletableFuture<String> acquirePollingBounded(long budgetMs) {
        return acquirePollingBounded(null, budgetMs);
    }

    /**
     * 带注册 owner 的轮询锁获取（io-resource-owner §5.3 锁）：授予点登记
     * {@code lockAcquireOwner}（快路径与队头交接两处，语义同
     * {@link #acquire(long, TimeUnit, ResourceOwner)}）；LEGACY 传 null。包内可见：
     * 消费面是 {@code ModbusTransactionStrategy} 与 {@code DeviceSpecificModbusSource}
     * 的 owner 注入委托，不对集成开放。
     */
    CompletableFuture<String> acquirePollingBounded(ResourceOwner owner, long budgetMs) {
        if (budgetMs <= 0) {
            throw new IllegalArgumentException("acquirePollingBounded 要求 budgetMs > 0: " + budgetMs);
        }
        // 快路径：无竞争轮次零等待、零记账（与旧轮询契约的非阻塞授予段行为一致）
        String fast = acquireUncontended(owner);
        if (fast != null) {
            return CompletableFuture.completedFuture(fast);
        }
        // 锁忙：等待移交 IO 旁池线程（定时线程零 park），预算内经 acquire 排队等队头交接。
        // MDC 提交时捕获、旁池任务内恢复——完成点之后的依赖链（事务体→dispatchIo 帧捕获）
        // 在旁池线程上携带设备归属上下文，与快路径的定时线程形态归因一致。
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        CompletableFuture<String> out = new CompletableFuture<>();
        try {
            ioExecutor.execute(() -> {
                Map<String, String> previousMdc = MDC.getCopyOfContextMap();
                try {
                    if (mdcContext != null) {
                        MDC.setContextMap(mdcContext);
                    }
                    String key = acquire(budgetMs, TimeUnit.MILLISECONDS, owner);
                    if (key == null) {
                        // 真弃轮才记账：预算耗尽/队列满（等到锁的轮次不计，观测不说谎）
                        recordPollingSkip(budgetMs);
                    }
                    out.complete(key);
                } finally {
                    if (mdcContext != null) {
                        if (previousMdc != null) {
                            MDC.setContextMap(previousMdc);
                        } else {
                            MDC.clear();
                        }
                    }
                }
            });
        } catch (RejectedExecutionException poolSaturated) {
            // 旁池饱和（线程+有界队全满）：本周期弃轮，下一周期再试（源可自愈）
            recordPollingSkip(budgetMs);
            return CompletableFuture.completedFuture(null);
        }
        return out;
    }

    /**
     * 无竞争快路径取锁（旧轮询契约的授予段）：锁忙返回 null，等待/弃轮由调用方
     * 决定——是否真弃轮取决于等待结果，记账不在此处。
     */
    private String acquireUncontended(ResourceOwner owner) {
        String requestKey = generateRequestKey();
        lock.lock();
        try {
            reapGhostLockIfStale("acquire");
            if (currentKey == null) {
                currentKey = requestKey;
                lockAcquireTime = System.currentTimeMillis();
                lockAcquireThread = Thread.currentThread().getName();
                lockAcquireOwner = owner;
                return requestKey;
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 真弃轮记账（E2/R3 可观测性：放弃必须可计数，禁静默）：计数 + 限频 warn。
     * 持锁方快照在锁内取，避免读到撕裂的 currentKey/时间戳组合。
     */
    private void recordPollingSkip(long budgetMs) {
        long skips = lockBusySkipCount.incrementAndGet();
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            if (now - lastBusySkipLogAt >= BUSY_SKIP_LOG_INTERVAL_MS) {
                lastBusySkipLogAt = now;
                log.warn("Polling bounded-acquire budget exhausted (" + budgetMs + "ms): modbusInfo=" + modbusInfo
                        + ", total skips=" + skips + ", lock currently held by: " + currentKey
                        + " (acquired at " + lockAcquireTime + " by thread " + lockAcquireThread + ")");
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 释放锁
     * @param releaseKey 要释放的锁标识
     * @return 释放是否成功
     */
    public boolean release(String releaseKey) {
        lock.lock();
        try {
            if (currentKey != null && currentKey.equals(releaseKey)) {
                currentKey = null;
                lockAcquireTime = 0;
                lockAcquireThread = null;
                lockAcquireOwner = null;
                // 唤醒下一个等待的请求
                if (!waitQueue.isEmpty()) {
                    condition.signal(); // 唤醒等待队列中的第一个线程
                }
                return true;
            }
            log.warn( "Invalid release key: " + releaseKey);
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** 当前持锁 owner（观测面；null=无主/已清）。sendTraced 捕获点直读同名字段。 */
    ResourceOwner getLockAcquireOwner() {
        return lockAcquireOwner;
    }

    private String generateRequestKey() {
        // 生成唯一请求标识（示例：时间戳+线程ID）
        return System.currentTimeMillis() + "-" + Thread.currentThread().getId();
    }

    /**
     * 幽灵锁收割（Q-1/Q-2 二轮根因修复，与 serial SerialSourcePort 同型）：事务级硬超时
     * 保证 release 必执行，但 release 的执行链可能整体丢失（计时任务入队被拒、持有线程被
     * master.send 无界等待永久吸收等）——currentKey 成永久幽灵锁，后续 acquire 只能超时，
     * 源永久瘫痪（live 实证）。acquire 入口与 forceRecoverTransport 双点检查：持锁时长超过
     * {@link #ghostReapThresholdMs} 即按 release 同一状态机强制清零 + signal 等待者。
     * 必须在已持有 {@code lock} 的临界区内调用。
     *
     * @param trigger 触发点标识（日志定位用：acquire / transport-wedge-recovery）
     */
    private void reapGhostLockIfStale(String trigger) {
        if (currentKey == null || lockAcquireTime <= 0) {
            return;
        }
        long heldMs = System.currentTimeMillis() - lockAcquireTime;
        if (heldMs <= ghostReapThresholdMs) {
            return;
        }
        log.error("[MODBUS-GHOST-LOCK-REAPED] trigger={}, 幽灵锁持锁 {}ms 超阈值 {}ms（持锁 key={}, 持锁线程={}），"
                        + "按 release 同一状态机强制清零，等待者 {} 个被唤醒, modbusInfo: {}",
                trigger, heldMs, ghostReapThresholdMs, currentKey, lockAcquireThread, waitQueue.size(), modbusInfo);
        // 与 release() 完全一致的状态清零 + 唤醒（同一状态机，无双路径漂移）
        currentKey = null;
        lockAcquireTime = 0;
        lockAcquireThread = null;
        lockAcquireOwner = null;
        if (!waitQueue.isEmpty()) {
            condition.signal();
        }
    }

    private void openModbus() {
        try {
            this.modbusMaster = ModbusMasterFactory.createModbusMaster(modbusInfo);
            modbusMaster.init();
        } catch (ModbusInitException e) {
            log.error( "Failed to initialize Modbus master. modbusInfo: " + modbusInfo.toString(), e);
        }
    }

    /**
     * 关闭 Modbus 连接。
     * 子类必须覆写此方法以实现正确的资源释放。
     * 共享连接通过 {@link #closeModbus(String)} 引用计数管理。
     *
     * @throws UnsupportedOperationException 默认实现抛出异常，强制子类实现
     * @see #closeModbus(String identity)
     */
    public void closeModbus() {
        throw new UnsupportedOperationException("Subclasses must implement closeModbus()");
    }

    /**
     * LEGACY 字符串身份注销（迁移过渡期旧签名）：包装 legacy owner 走 owner 摘账路径。
     *
     * @param identity 注册时使用的字符串身份
     * @throws IllegalArgumentException 身份未注册
     */
    public void closeModbus(String identity) {
        closeModbus(ResourceOwner.legacy(identity));
    }

    /**
     * 注册方 owner 注销（io-resource-owner §5.3）：从账本摘除本 owner 条目；账本摘空才
     * 销毁底层资源（引用计数语义不变，粒度从字符串升为 owner——同连接多设备各摘各的）。
     *
     * @param owner 注册时的 owner（ownerKey 等价即视为同一注册方）
     * @throws IllegalArgumentException owner 未注册
     */
    public void closeModbus(ResourceOwner owner) {
        Objects.requireNonNull(owner, "owner");
        // Check if owner exists in ledger
        if (!registeredOwners.containsKey(owner.ownerKey())) {
            throw new IllegalArgumentException("Identity not found: " + owner.ownerKey());
        }

        // Remove the owner from ledger
        registeredOwners.remove(owner.ownerKey());

        // Only close Modbus if no owners are registered
        if (registeredOwners.isEmpty()) {
            destroyResources();
            log.info( "Modbus connection closed by " + owner.ownerKey());
        } else {
            log.info( "Identity removed but connection kept open: " + owner.ownerKey() +
                              ", remaining integrations: " + registeredOwners.size());
        }
    }

    /**
     * 销毁底层资源（ModbusMaster）。
     * TCP/RTU 传输资源差异由 {@link ModbusMasterFactory#destroyMaster(ModbusMaster)} 统一处理。
     *
     * <p>IO 旁池（R3 期 4）不随单源关停：池归 ModbusIntegration 生命周期管理
     * （onInit 初始化 / onRelease 在全部源销毁后统一 shutdown），在途 send 自然跑完；
     * 死源语义由 destroyed 标志表达（见 {@link #isModbusOpen()}）。
     */
    protected void destroyResources() {
        ModbusMasterFactory.destroyMaster(modbusMaster);
        destroyed = true; // 死源显式标志（isModbusOpen 防线，见下）
    }

    /**
     * 挂死传输强拆（Q-1/A2 自愈）：事务硬超时视为底层传输挂死——车道线程钉死在
     * {@code master.send} 的阻塞写上，且 modbus4j {@code sendImpl} 与 {@code destroy()}/{@code init()}
     * 同步在同一 master 监视锁上，持锁者不返回时从本线程调用它们会永久阻塞
     * （把挂死扩散到超时调度线程，二次事故）。因此唯一安全动作 = 从旁关闭底层传输原语
     * （TCP socket / RTU 串口 fd），使阻塞 IO 立即异常返回、车道线程得救；
     * keepAlive（TCP）/每事务重开（RTU）的既有路径在下一事务自动重建连接。
     * 实现委托 {@link ModbusMasterFactory#forceRecover(ModbusMaster, String)}。
     *
     * @param reason 触发原因（日志定位用，如 transaction-hard-timeout）
     */
    public void forceRecoverTransport(String reason) {
        if (destroyed) {
            log.warn("Skip wedge recovery, source already destroyed. modbusInfo: {}", modbusInfo);
            return;
        }
        ModbusMaster master = this.modbusMaster;
        if (master == null) {
            return;
        }
        log.error("[MODBUS-WEDGE-RECOVERY] 强拆挂死传输, 原因: {}, modbusInfo: {}", reason, modbusInfo);
        // Q-1/Q-2 二轮：强拆传输只救 socket/fd，不清逻辑锁——若 currentKey 已成幽灵
        //（release 链整体丢失），下一个 acquire 仍撞同一把逻辑锁，恢复循环不收敛。
        // 此处按持锁时长阈值强制清锁（与 acquire 入口同一收割状态机）。
        lock.lock();
        try {
            reapGhostLockIfStale("transport-wedge-recovery");
        } finally {
            lock.unlock();
        }
        ModbusMasterFactory.forceRecover(master, reason);
    }

    public boolean isModbusOpen() {
        // destroyed 只在 destroyResources() 里置位（唯一调用点），故 destroyed==true 即"源已销毁"。
        // 必须纳入判断：modbus4j 的 modbusMaster.destroy() 不重置 isInitialized flag（既知），
        // 仅判 master.isInitialized() 会让死 source 仍报 open → createOrGetSource 死源清理（!isModbusOpen）失效
        // → 同连接后继新设备拿到已销毁的死 source → readAndUpdate 提交到死源的事务永不执行
        // → 无数据，须重启 core 才恢复（bug-record-20260728-170000）。
        // 死源判定为显式 destroyed 标志（volatile，跨线程可见）——池/执行器形态与单源生死
        // 无关（IO 旁池归集成管理），不得借任何执行器状态判死。
        return modbusMaster != null && modbusMaster.isInitialized() && !destroyed;
    }

    /**
     * 粗略获取当前等待队列的大小
     * 适合监控队列长度场景使用，不能作为抢占锁精准计数
     * @return
     */
    public int getWaitingCount() {
        return waitQueue.size();
    }


}
