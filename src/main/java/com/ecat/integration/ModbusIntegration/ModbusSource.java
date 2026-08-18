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
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import com.ecat.core.CommTrace.CommTraceBuffer;
import com.ecat.core.CommTrace.CommTraceTransport;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;

public class ModbusSource {
    private final Log log = LogFactory.getLogger(getClass());
    private final Lock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();
    @Getter
    private final int maxWaiters; // 最大等待请求数
    @Getter
    private final int waitTimeoutMs; // 等待超时时间（毫秒）
    private String currentKey;    // 当前持有锁的key
    private final Queue<String> waitQueue = new LinkedList<>(); // 等待队列（保存请求标识）
    // 通讯帧捕获事务配对序号（TX/RX 同 txnId）
    private static final AtomicLong TXN_COUNTER = new AtomicLong(0);

    private ModbusMaster modbusMaster;
    @Getter
    private ModbusInfo modbusInfo;
    private List<String> registeredIntegrations;
    // 事务车道：生产=引擎车道视图（共享调度引擎，同连接串行/异连接并行，见 ModbusSourceLanes）；
    // 无 core 上下文（单测/独立运行）=本地兜底单线程（modbus-source-{conn} 命名）。
    // delegateMode 下为 null（不创建线程，事务走 delegate 的车道）。
    private final ExecutorService executor;
    // executor 为共享引擎车道视图时为 true：视图无独立生命周期（shutdown 抛 UOE），
    // destroyResources 只置 destroyed 标志、不得 shutdown 共享引擎；本地兜底则随源 shutdown。
    private final boolean engineOwnedLane;
    // 死源显式标志（destroyResources 置位）：isModbusOpen 的死源防线。
    // 不得借用 executor.isShutdown() 判死——引擎视图 isShutdown 恒随引擎与单源生死无关（借壳判定失效）。
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
     * @param delegateMode  是否为委托模式（DeviceSpecificModbusSource 使用，不创建 executor）
     */
    protected ModbusSource(ModbusInfo modbusInfo, int maxWaiters, int waitTimeoutMs, boolean skipOpen, boolean delegateMode) {
        this(modbusInfo, maxWaiters, waitTimeoutMs, skipOpen, delegateMode, "source");
    }

    /**
     * 构造函数（带车道名）
     *
     * @param laneName 连接标识（IP:port 或串口名），作为事务车道键的连接段
     *                （引擎车道 modbus-source:{laneName}；无 core 时本地兜底线程 modbus-source-{laneName}-N），
     *                线程/车道治理时可直接定位到连接
     */
    protected ModbusSource(ModbusInfo modbusInfo, int maxWaiters, int waitTimeoutMs, boolean skipOpen, boolean delegateMode, String laneName) {
        this.maxWaiters = maxWaiters; // 设置资源最大等待请求数
        this.waitTimeoutMs = waitTimeoutMs; // 设置资源等待超时时间
        this.modbusInfo = modbusInfo;
        this.registeredIntegrations = new ArrayList<>();
        // 事务车道（IO 收敛 P2）：同一 source 上的读/写事务按序执行（同连接串行防帧碰撞），
        // 异连接并行。解析与所有权契约见 ModbusSourceLanes（生产=引擎车道，单测=本地兜底单线程）。
        if (delegateMode) {
            this.executor = null;
            this.engineOwnedLane = false;
        } else {
            ModbusSourceLanes.Lane lane = ModbusSourceLanes.resolve(laneName);
            this.executor = lane.getExecutor();
            this.engineOwnedLane = lane.isEngineOwned();
        }
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
    protected void initSerialMaster(ModbusSerialInfo serialInfo, com.ecat.integration.SerialIntegration.SerialSource serialSource) {
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
    private ModbusMessage sendTraced(com.serotonin.modbus4j.msg.ModbusRequest request) throws ModbusTransportException {
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
        CommTraceBuffer.instance().tx(transport, portId, encodeMessage(request), txnId);
        try {
            ModbusMessage response = modbusMaster.send(request);
            double durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;
            CommTraceBuffer.instance().rx(transport, portId,
                    response != null ? encodeMessage(response) : new byte[0], txnId, durationMs);
            return response;
        } catch (ModbusTransportException e) {
            CommTraceBuffer.instance().error(transport, portId);
            throw e;
        }
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
        return CompletableFuture.supplyAsync(() -> {
            try {
                ReadCoilsRequest request = new ReadCoilsRequest(slaveId, startAddress, numberOfBits);
                return (ReadCoilsResponse) sendTraced(request);
            } catch (ModbusTransportException e) {
                log.error( "Error reading coils. slaveId: " + slaveId +
                        " (startAddress: " + startAddress + ", numberOfBits: " + numberOfBits +
                        ", modbusInfo=" + modbusInfo + ")", e);
                return null;
            }
        }, executor);
    }

    protected CompletableFuture<ReadDiscreteInputsResponse> readDiscreteInputsWithSlaveId(Integer slaveId, int startAddress, int numberOfBits) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ReadDiscreteInputsRequest request = new ReadDiscreteInputsRequest(slaveId, startAddress, numberOfBits);
                return (ReadDiscreteInputsResponse) sendTraced(request);
            } catch (ModbusTransportException e) {
                log.error( "Error reading discrete inputs. slaveId: " + slaveId +
                        " (startAddress: " + startAddress + ", numberOfBits: " + numberOfBits +
                        ", modbusInfo=" + modbusInfo + ")", e);
                return null;
            }
        }, executor);
    }

    protected CompletableFuture<ReadExceptionStatusResponse> readExceptionStatusWithSlaveId(Integer slaveId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ReadExceptionStatusRequest request = new ReadExceptionStatusRequest(slaveId);
                return (ReadExceptionStatusResponse) sendTraced(request);
            } catch (ModbusTransportException e) {
                log.error( "Error reading exception status. slaveId: " + slaveId + ", modbusInfo=" + modbusInfo, e);
                return null;
            }
        }, executor);
    }

    protected CompletableFuture<ReadHoldingRegistersResponse> readHoldingRegistersWithSlaveId(Integer slaveId, int startAddress, int numberOfRegisters) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ReadHoldingRegistersRequest request = new ReadHoldingRegistersRequest(slaveId, startAddress, numberOfRegisters);
                return (ReadHoldingRegistersResponse) sendTraced(request);
            } catch (ModbusTransportException e) {
                log.error( "Error reading holding registers. slaveId: " + slaveId +
                        " (startAddress: " + startAddress + ", numberOfRegisters: " + numberOfRegisters +
                        ", modbusInfo=" + modbusInfo + ")", e);
                return null;
            }
        }, executor);
    }

    protected CompletableFuture<ReadInputRegistersResponse> readInputRegistersWithSlaveId(Integer slaveId, int startAddress, int numberOfRegisters) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ReadInputRegistersRequest request = new ReadInputRegistersRequest(slaveId, startAddress, numberOfRegisters);
                return (ReadInputRegistersResponse) sendTraced(request);
            } catch (ModbusTransportException e) {
                log.error( "Error reading input registers. slaveId: " + slaveId +
                        " (startAddress: " + startAddress + ", numberOfRegisters: " + numberOfRegisters +
                        ", modbusInfo=" + modbusInfo + ")", e);
                return null;
            }
        }, executor);
    }

    protected CompletableFuture<ReportSlaveIdResponse> reportSlaveIdWithSlaveId(Integer slaveId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ReportSlaveIdRequest request = new ReportSlaveIdRequest(slaveId);
                return (ReportSlaveIdResponse) sendTraced(request);
            } catch (ModbusTransportException e) {
                log.error( "Error reporting slave ID. slaveId: " + slaveId + ", modbusInfo=" + modbusInfo, e);
                return null;
            }
        }, executor);
    }

    protected CompletableFuture<WriteCoilResponse> writeCoilWithSlaveId(Integer slaveId, int address, boolean value) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                WriteCoilRequest request = new WriteCoilRequest(slaveId, address, value);
                return (WriteCoilResponse) sendTraced(request);
            } catch (ModbusTransportException e) {
                log.error( "Error writing coil. slaveId: " + slaveId +
                        " (address: " + address + ", value: " + value +
                        ", modbusInfo=" + modbusInfo + ")", e);
                return null;
            }
        }, executor);
    }

    protected CompletableFuture<WriteCoilsResponse> writeCoilsWithSlaveId(Integer slaveId, int startAddress, boolean[] values) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                WriteCoilsRequest request = new WriteCoilsRequest(slaveId, startAddress, values);
                return (WriteCoilsResponse) sendTraced(request);
            } catch (ModbusTransportException e) {
                log.error( "Error writing coils. slaveId: " + slaveId +
                        " (startAddress: " + startAddress + ", values: " + Arrays.toString(values) +
                        ", modbusInfo=" + modbusInfo + ")", e);
                return null;
            }
        }, executor);
    }

    protected CompletableFuture<WriteMaskRegisterResponse> writeMaskRegisterWithSlaveId(Integer slaveId, int address, int andMask, int orMask) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                WriteMaskRegisterRequest request = new WriteMaskRegisterRequest(slaveId, address, andMask, orMask);
                return (WriteMaskRegisterResponse) sendTraced(request);
            } catch (ModbusTransportException e) {
                log.error( "Error writing mask register. slaveId: " + slaveId +
                        " (address: " + address + ", andMask: " + andMask + ", orMask: " + orMask +
                        ", modbusInfo=" + modbusInfo + ")", e);
                return null;
            }
        }, executor);
    }

    protected CompletableFuture<WriteRegisterResponse> writeRegisterWithSlaveId(Integer slaveId, int address, int value) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                WriteRegisterRequest request = new WriteRegisterRequest(slaveId, address, value);
                return (WriteRegisterResponse) sendTraced(request);
            } catch (ModbusTransportException e) {
                log.error( "Error writing register. slaveId: " + slaveId +
                        " (address: " + address + ", value: " + value +
                        ", modbusInfo=" + modbusInfo + ")", e);
                return null;
            }
        }, executor);
    }

    protected CompletableFuture<WriteRegistersResponse> writeRegistersWithSlaveId(Integer slaveId, int startAddress, short[] values) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                WriteRegistersRequest request = new WriteRegistersRequest(slaveId, startAddress, values);
                return (WriteRegistersResponse) sendTraced(request);
            } catch (ModbusTransportException e) {
                log.error( "Error writing registers. slaveId: " + slaveId +
                        " (startAddress: " + startAddress + ", values: " + Arrays.toString(values) +
                        ", modbusInfo=" + modbusInfo + ")", e);
                return null;
            }
        }, executor);
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

    public void registerIntegration(String identity) {
        registeredIntegrations.add(identity);
    }

    protected void removeIntegration(String identity) {
        registeredIntegrations.remove(identity);
    }

    /**
     * 尝试获取锁，支持等待队列
     * @return 锁标识（成功获取或进入等待），null表示无法获取且超出等待队列容量
     */
    public String acquire() {
        return acquire(waitTimeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 尝试获取锁，支持等待队列和超时
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 锁标识（成功获取/唤醒或进入等待），null表示超时、超出等待队列容量，
     *         或唤醒后锁已被快速路径请求抢占（handed-off race 失败，按未取得总线重试）
     */
    public String acquire(long timeout, TimeUnit unit) {
        String requestKey = generateRequestKey(); // 生成唯一请求标识
        lock.lock();
        try {
            if (currentKey == null) {
                // 直接获取锁
                currentKey = requestKey;
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

    private String generateRequestKey() {
        // 生成唯一请求标识（示例：时间戳+线程ID）
        return System.currentTimeMillis() + "-" + Thread.currentThread().getId();
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

    public void closeModbus(String identity) {
        // Check if identity exists in registered integrations
        if (!registeredIntegrations.contains(identity)) {
            throw new IllegalArgumentException("Identity not found: " + identity);
        }

        // Remove the integration from registered list
        registeredIntegrations.remove(identity);

        // Only close Modbus if no integrations are registered
        if (registeredIntegrations.isEmpty()) {
            destroyResources();
            log.info( "Modbus connection closed by " + identity);
        } else {
            log.info( "Identity removed but connection kept open: " + identity +
                              ", remaining integrations: " + registeredIntegrations.size());
        }
    }

    /**
     * 供同包测试验证事务车道（引擎视图/本地兜底）的解析与生命周期（非公开 API）。
     */
    ExecutorService getLaneExecutor() {
        return executor;
    }

    /**
     * 销毁底层资源（ModbusMaster、事务车道）。
     * TCP/RTU 传输资源差异由 {@link ModbusMasterFactory#destroyMaster(ModbusMaster)} 统一处理。
     *
     * <p>事务车道（IO 收敛 P2）：引擎车道视图是共享调度引擎的窗口，销毁源时不得 shutdown
     * （视图自身 shutdown 抛 UOE）——车道随在途任务自然排空（单事务被引擎 30s 硬超时界住，
     * 最坏 ~12s），不残留任务引用；仅本地兜底 executor（无 core 单测）归源所有，随源 shutdown。
     * 死源语义由 destroyed 标志表达（见 {@link #isModbusOpen()}）。
     */
    protected void destroyResources() {
        ModbusMasterFactory.destroyMaster(modbusMaster);
        destroyed = true; // 死源显式标志（isModbusOpen 防线，见下）
        if (executor != null && !engineOwnedLane) {
            executor.shutdown();
        }
    }

    public boolean isModbusOpen() {
        // destroyed 只在 destroyResources() 里置位（唯一调用点），故 destroyed==true 即"源已销毁"。
        // 必须纳入判断：modbus4j 的 modbusMaster.destroy() 不重置 isInitialized flag（既知），
        // 仅判 master.isInitialized() 会让死 source 仍报 open → createOrGetSource 死源清理（!isModbusOpen）失效
        // → 同连接后继新设备拿到已销毁的死 source → readAndUpdate 提交到死源的事务永不执行
        // → 无数据，须重启 core 才恢复（bug-record-20260728-170000）。
        // 旧实现借 executor.isShutdown() 判死；引擎车道视图 isShutdown 恒随引擎与单源生死无关（且
        // 不得调视图 shutdown），故死源判定平移为显式 destroyed 标志（volatile，跨线程可见）。
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
