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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
    @Getter
    private final int maxWaiters; // 最大等待请求数
    @Getter
    private final int waitTimeoutMs; // 等待超时时间（毫秒）

    /**
     * 幽灵收割阈值语义（现役形态=连接执行者的 HELD hold deadline）：release 链整体丢失
     * （持有线程被 master.send 无界吸收等）时强制收场，防连接锁死。合法事务的硬超时
     * 上限 = requestTimeout×6（boundedReadWaitMs），秒级~十秒级，5 分钟远超任何合法
     * 持锁时长。package-private 非 final 供红测缩短。
     */
    static final long DEFAULT_GHOST_REAP_THRESHOLD_MS = 300_000L;
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
    // 每连接串行执行者（serial 4.1.0 SerialPortExecutor 同模式、连接域独立实现）：
    // 总线互斥（HELD 有界优先队列）、帧序（无界 FIFO 单在飞）、事务/帧 deadline 全部
    // 收敛到单线程状态机——取代旧 ReentrantLock+waitQueue+currentKey+幽灵收割+pendingSends
    // 手写单飞九件套（无锁可泄漏、无幽灵可收割，机制细节见执行者类注）。
    private final ModbusConnectionExecutor connectionExecutor;
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
     * @param maxWaiters    最大等待请求数（连接执行者 HELD 队列容量）
     * @param waitTimeoutMs 等待超时时间（毫秒）
     * @param skipOpen      是否跳过 openModbus()（RTU 新模式跳过，由 initSerialMaster 初始化）
     * @param delegateMode  是否为委托模式（DeviceSpecificModbusSource 使用；send 委托共享源执行，
     *                      本实例的旁池引用与执行者不参与其事务路径）
     */
    protected ModbusSource(ModbusInfo modbusInfo, int maxWaiters, int waitTimeoutMs, boolean skipOpen, boolean delegateMode) {
        this.maxWaiters = maxWaiters; // 设置资源最大等待请求数
        this.waitTimeoutMs = waitTimeoutMs; // 设置资源等待超时时间
        this.modbusInfo = modbusInfo;
        this.ioExecutor = ModbusIoPool.executor();
        // 帧 deadline 与策略层事务硬超时同源（boundedReadWaitMs）：requestTimeout×6，
        // 覆盖 modbus4j send 的 timeout×(retries+1) 上界再留余量；非法值不武装（同守卫）。
        // 委托视图（DeviceSpecificModbusSource 经 mock delegate 构造等）modbusInfo 可为
        // null——该形态不参与本实例事务路径，执行者仅作占位（connectionKey 用明示标签）。
        long frameDeadlineMs = modbusInfo != null && modbusInfo.getRequestTimeoutMs() > 0
                ? (long) modbusInfo.getRequestTimeoutMs() * ModbusTransactionStrategy.BOUNDED_READ_WAIT_FACTOR
                : 0L;
        this.connectionExecutor = new ModbusConnectionExecutor(
                modbusInfo != null ? connectionKey(modbusInfo) : "delegate-view", maxWaiters, frameDeadlineMs,
                DEFAULT_GHOST_REAP_THRESHOLD_MS,
                factory -> sendTraced(factory.create()), this::forceRecoverTransport);
        if (!skipOpen) {
            openModbus();
        }
    }

    /**
     * 连接仲裁键（执行者线程名与日志定位用）：与 {@code ModbusIntegration.getConnectionIdentity}
     * 同口径——TCP ip:port / 串口 portName。未知类型无法建立连接，抛明示而非猜测。
     */
    private static String connectionKey(ModbusInfo info) {
        if (info instanceof ModbusTcpInfo) {
            ModbusTcpInfo tcp = (ModbusTcpInfo) info;
            return tcp.getIpAddress() + ":" + tcp.getPort();
        }
        if (info instanceof ModbusSerialInfo) {
            return ((ModbusSerialInfo) info).getPortName();
        }
        throw new IllegalStateException("未知的Modbus协议类型: " + info.getClass().getName());
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
        // 捕获点权威归因（io-resource-owner §5.3）：帧归属=当前 HELD 持有 owner（modbus
        // 事务恒在持有期发起，同连接多 slave 逐笔归属；执行者授予点登记）；无持有/LEGACY
        // 时落源注册兜底（见 frameAttributionOwner——归因次序 owner > MDC > 回填在
        // CommTraceBuffer 仍成立）。单次取值冻结 TX/RX 同帧同主（持有超时清槽不撕裂同一对
        // 的归因）。
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
     * 本帧权威 owner（归因次序：持槽 owner &gt; 源注册兜底 &gt; null 落线程 MDC）。
     *
     * <p>无锁兜底（bug-record-20260913-111500）：裸 dispatchIo 读事务（mgd 轮询形态）
     * 不经源锁，持槽 owner 恒 null，帧归因此前只剩提交线程 MDC——RECONFIGURE
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
        ResourceOwner heldOwner = connectionExecutor.currentOwner();
        if (heldOwner != null) {
            return heldOwner;
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

    /**
     * 把单次阻塞 master.send（含写+等回音）的帧对话提交给连接执行者（serial 模式）。
     *
     * <p>发起段 O(1)：入本连接无界帧队列即返回 CF——执行者按「同刻至多一帧在飞」派发，
     * 阻塞 send 在 {@code ecat-modbus-io-N} 旁池 worker 承接（引擎 lane worker 不被响应窗
     * 钉死；执行者线程只排序不执行 send）。帧序与互斥由执行者单线程状态机保证
     * （取代旧 pendingSends+sendDraining 手写单飞；等待帧是纯 Java 对象，排队零线程消耗）。
     *
     * <p>失败表面不变：请求构造/传输异常（ModbusTransportException）→ CF 完成值 null
     * （调用方 null 语义不变）；未知 RuntimeException → CF 异常完成如实上抛；旁池饱和
     * 拒绝 → 本帧 CF 立即异常完成（RejectedExecutionException）——事务级「过期即弃」：
     * 轮询本周期放弃下周期再试 / 写闸记 FAILED，不阻塞发起段。
     *
     * @param requestFactory 单帧请求构造（旁池线程上执行）
     * @param errorLabel     传输异常时的 ERROR 日志文案（含 slaveId/modbusInfo 定位信息）
     * @return 响应 CF：成功=响应对象；传输异常=null；池饱和=RejectedExecutionException 异常完成
     */
    private <R extends ModbusMessage> CompletableFuture<R> dispatchIo(ModbusConnectionExecutor.IoRequestFactory requestFactory, String errorLabel) {
        return cast(connectionExecutor.submitFrame(requestFactory, errorLabel, MDC.getCopyOfContextMap()));
    }

    /** 泛型投影（执行者 CF 按 ModbusMessage 完成，调用方按具体响应类型消费——旧形态不变）。 */
    @SuppressWarnings("unchecked")
    private static <R extends ModbusMessage> CompletableFuture<R> cast(CompletableFuture<? extends ModbusMessage> future) {
        return (CompletableFuture<R>) future;
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
     * 尝试获取连接事务槽（HELD），支持等待队列
     * @return 锁标识（成功获取或进入等待），null表示无法获取且超出等待队列容量
     */
    public String acquire() {
        return acquire(waitTimeoutMs, TimeUnit.MILLISECONDS, null);
    }

    /**
     * 带注册 owner 获取连接事务槽（io-resource-owner §5.3 锁）：授予点把 owner 登记
     * 为持有者（事务期间 TX/RX 捕获点的权威归因来源），release 与持有超时强制收场按
     * 同一状态机清除。LEGACY owner 请传 null——LEGACY 无设备身份字段，注入反而会压制
     * 线程 MDC 归因（过渡期行为不得回退）。
     *
     * @param owner 本次事务的注册 owner（null=无主事务，保持现状）
     * @return 锁标识（成功获取或进入等待），null表示无法获取且超出等待队列容量
     */
    public String acquire(ResourceOwner owner) {
        return acquire(waitTimeoutMs, TimeUnit.MILLISECONDS, owner);
    }

    /**
     * 尝试获取连接事务槽，支持等待队列和超时
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 锁标识（成功获取/唤醒或进入等待），null表示超时、超出等待队列容量，
     *         或被高优先级请求挤出
     */
    public String acquire(long timeout, TimeUnit unit) {
        return acquire(timeout, unit, null);
    }

    /**
     * 事务槽获取核心（owner 登记版）：委托连接执行者——无竞争快路径内联授予（调用
     * 线程，与旧快速路径同形态），有竞争入有界优先队列并阻塞等待授予回执。
     *
     * @param timeout 超时时间
     * @param unit 时间单位
     * @param owner 本次事务的注册 owner（null=无主事务）
     * @return 锁标识；语义同 {@link #acquire(long, TimeUnit)}
     */
    public String acquire(long timeout, TimeUnit unit, ResourceOwner owner) {
        return connectionExecutor.acquire(owner, unit.toMillis(timeout));
    }

    /** 轮询真弃轮计数（E2/R3 记账：有界等待预算耗尽才计，禁静默）。 */
    private final AtomicLong lockBusySkipCount = new AtomicLong();
    /** 锁忙放弃日志限频时间戳（volatile；竞速重复写仅可能多打一条限频内 warn，无语义影响）。 */
    private volatile long lastBusySkipLogAt;
    /** 锁忙放弃日志限频间隔：默认 60s 一条（饱和期 ~30 次/min 的放弃若不限频会刷爆日志）。 */
    static final long BUSY_SKIP_LOG_INTERVAL_MS = 60_000L;

    /** 获取累计真弃轮次数（轮询有界等待预算耗尽的计数，运行时可观测用）。 */
    public long getLockBusySkipCount() {
        return lockBusySkipCount.get();
    }

    /**
     * 轮询锁获取契约（20260913-073600 修复，方案 c：轮询并入 FIFO 有界等待——执行者
     * 形态下排队与授予由连接执行者承载）：无竞争快路径直达（授予在调用线程完成，
     * 事务体随 CF 依赖链在调用线程执行）；锁忙时排队等待由执行者线程承载（提交线程
     * 零 park），授予回执经 {@code thenAcceptAsync} 卸载 IO 旁池线程——事务体在旁池
     * 线程执行（MDC 已恢复到提交时快照），执行者线程永不跑设备代码。轮询与写命令同队
     * 同公平（高挤普通的被挤方=轮询方，语义见执行者 admit）。
     *
     * <p>返回 CF 语义：完成值非 null=锁标识；null=本周期真弃轮（预算耗尽/等待
     * 队列满/被高优先级挤出/旁池饱和拒绝），源侧记账。等到了锁的轮次不计入
     * {@link #getLockBusySkipCount()}（观测不说谎）。
     *
     * @param budgetMs 锁等待预算（毫秒，须 &gt; 0；SDK 侧按轮询周期 clamp）
     * @return 锁标识 CF；null 完成=本周期弃轮
     */
    public CompletableFuture<String> acquirePollingBounded(long budgetMs) {
        return acquirePollingBounded(null, budgetMs);
    }

    /**
     * 带注册 owner 的轮询锁获取（io-resource-owner §5.3 锁）：授予点登记持有 owner
     * （快路径与队列授予两处，语义同 {@link #acquire(long, TimeUnit, ResourceOwner)}）；
     * LEGACY 传 null。包内可见：消费面是 {@code ModbusTransactionStrategy} 与
     * {@code DeviceSpecificModbusSource} 的 owner 注入委托，不对集成开放。
     */
    CompletableFuture<String> acquirePollingBounded(ResourceOwner owner, long budgetMs) {
        if (budgetMs <= 0) {
            throw new IllegalArgumentException("acquirePollingBounded 要求 budgetMs > 0: " + budgetMs);
        }
        // 排队授予（快路径内联授予的 conv.granted 已完成，thenAcceptAsync 同步执行在
        // 调用线程——与旧「快路径事务体在调用线程执行」形态一致；慢路径完成点在 IO
        // 旁池线程，MDC 恢复到提交时快照，帧捕获携带设备归属）
        CompletableFuture<String> granted = connectionExecutor.acquireAsync(owner, budgetMs);
        if (granted == null) {
            // 队满且无可挤（满拒）：本周期真弃轮，记账后按 null 完成（策略层转跳过异常）
            recordPollingSkip(budgetMs);
            return CompletableFuture.completedFuture(null);
        }
        if (granted.isDone()) {
            return granted; // 快路径：授予已在提交线程完成，事务体在调用线程执行（零 hop）
        }
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        CompletableFuture<String> out = new CompletableFuture<>();
        try {
            granted.thenAcceptAsync(key -> {
                Map<String, String> previousMdc = MDC.getCopyOfContextMap();
                try {
                    if (mdcContext != null) {
                        MDC.setContextMap(mdcContext);
                    }
                    if (key == null) {
                        // 真弃轮才记账：预算耗尽/被挤出（等到锁的轮次不计，观测不说谎）
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
            }, ioExecutor);
        } catch (RejectedExecutionException poolSaturated) {
            // 旁池饱和（线程+有界队全满）：本周期弃轮，下一周期再试（源可自愈）
            recordPollingSkip(budgetMs);
            return CompletableFuture.completedFuture(null);
        }
        return out;
    }

    /**
     * 真弃轮记账（E2/R3 可观测性：放弃必须可计数，禁静默）：计数 + 限频 warn。
     * 持有者快照经执行者观测面直读（单写者+授予后字段稳定，无撕裂）。
     */
    private void recordPollingSkip(long budgetMs) {
        long skips = lockBusySkipCount.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now - lastBusySkipLogAt >= BUSY_SKIP_LOG_INTERVAL_MS) {
            lastBusySkipLogAt = now;
            log.warn("Polling bounded-acquire budget exhausted (" + budgetMs + "ms): modbusInfo=" + modbusInfo
                    + ", total skips=" + skips + ", " + connectionExecutor.lockHolderSnapshot());
        }
    }

    /**
     * 释放连接事务槽
     * @param releaseKey 要释放的锁标识
     * @return 释放是否成功（false=key 不匹配——含持有超时已被强制收场的竞速）
     */
    public boolean release(String releaseKey) {
        return connectionExecutor.release(releaseKey);
    }

    /** 当前持有 owner（观测面；null=无主/已清）。sendTraced 捕获点直读同语义值。 */
    ResourceOwner getLockAcquireOwner() {
        return connectionExecutor.currentOwner();
    }

    /**
     * 持有者真相快照（观测面）：供源锁语义的消费方（策略层取锁失败日志）直读持有者
     * 身份与已持时长。形态与旧锁版本逐字一致（{@code lock currently held by: ...
     * (acquired at ... by thread ..., held <n>ms, owner=...)} / {@code lock currently
     * free}——「等待期间卡顿已自愈释放」的直接证据形态不变）。
     */
    String lockHolderSnapshot() {
        return connectionExecutor.lockHolderSnapshot();
    }

    /**
     * 红测注入口：缩短 HELD hold deadline（旧「幽灵锁收割阈值」测试缝，语义迁移为
     * 执行者持有超时强制收场阈值；仅同包测试使用，生产用默认 5 分钟）。
     */
    void setGhostReapThresholdMsForTest(long thresholdMs) {
        if (thresholdMs <= 0) {
            throw new IllegalArgumentException("ghostReapThresholdMs must be > 0, got: " + thresholdMs);
        }
        connectionExecutor.setHoldDeadlineMsForTest(thresholdMs);
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
     * 连接执行者 {@code markClosed}：空闲线程退出防源销毁后线程滞留（非拒绝态——
     * 销毁后帧/授予行为不变，见执行者类注「无 CLOSED」）。
     */
    protected void destroyResources() {
        ModbusMasterFactory.destroyMaster(modbusMaster);
        connectionExecutor.markClosed();
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
     * <p>调用面：策略层事务硬超时/apply 看门狗（经 IO 池卸载）+ 连接执行者帧 deadline
     * （同卸载形态）——旧「此处收割幽灵锁」段随锁机制退役：HELD 持有超时由执行者
     * hold deadline 强制收场，无逻辑锁可幽灵化。
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
        return connectionExecutor.getWaitingCount();
    }


}
