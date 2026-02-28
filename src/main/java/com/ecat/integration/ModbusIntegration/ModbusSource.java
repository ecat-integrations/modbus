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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
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

    private ModbusMaster modbusMaster;
    @Getter
    private ModbusInfo modbusInfo;
    private List<String> registeredIntegrations;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    protected ModbusSource(ModbusInfo modbusInfo) {
        this(modbusInfo, Const.DEFAULT_MAX_WAITERS, Const.DEFAULT_WAIT_TIMEOUT_MS); // 默认最大等待请求数为1，等待超时时间为Const.WAIT_TIMEOUT_MS
    }

    protected ModbusSource(ModbusInfo modbusInfo, int maxWaiters, int waitTimeoutMs) {
        this(modbusInfo, maxWaiters, waitTimeoutMs, false);
    }

    protected ModbusSource(ModbusInfo modbusInfo, int maxWaiters, int waitTimeoutMs, boolean skipOpen) {
        this.maxWaiters = maxWaiters; // 设置资源最大等待请求数
        this.waitTimeoutMs = waitTimeoutMs; // 设置资源等待超时时间
        this.modbusInfo = modbusInfo;
        this.registeredIntegrations = new ArrayList<>();
        if (!skipOpen) {
            openModbus();
        }
    }

    // 新增：带slaveId参数的内部方法
    protected CompletableFuture<ReadCoilsResponse> readCoilsWithSlaveId(Integer slaveId, int startAddress, int numberOfBits) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ReadCoilsRequest request = new ReadCoilsRequest(slaveId, startAddress, numberOfBits);
                return (ReadCoilsResponse) modbusMaster.send(request);
            } catch (ModbusTransportException e) {
                log.error( "Error reading coils. slaveId: " + slaveId +
                        " (startAddress: " + startAddress + ", numberOfBits: " + numberOfBits + ")", e);
                return null;
            }
        }, executor);
    }

    protected CompletableFuture<ReadDiscreteInputsResponse> readDiscreteInputsWithSlaveId(Integer slaveId, int startAddress, int numberOfBits) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ReadDiscreteInputsRequest request = new ReadDiscreteInputsRequest(slaveId, startAddress, numberOfBits);
                return (ReadDiscreteInputsResponse) modbusMaster.send(request);
            } catch (ModbusTransportException e) {
                log.error( "Error reading discrete inputs. slaveId: " + slaveId +
                        " (startAddress: " + startAddress + ", numberOfBits: " + numberOfBits + ")", e);
                return null;
            }
        }, executor);
    }

    protected CompletableFuture<ReadExceptionStatusResponse> readExceptionStatusWithSlaveId(Integer slaveId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ReadExceptionStatusRequest request = new ReadExceptionStatusRequest(slaveId);
                return (ReadExceptionStatusResponse) modbusMaster.send(request);
            } catch (ModbusTransportException e) {
                log.error( "Error reading exception status. slaveId: " + slaveId, e);
                return null;
            }
        }, executor);
    }

    protected CompletableFuture<ReadHoldingRegistersResponse> readHoldingRegistersWithSlaveId(Integer slaveId, int startAddress, int numberOfRegisters) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ReadHoldingRegistersRequest request = new ReadHoldingRegistersRequest(slaveId, startAddress, numberOfRegisters);
                return (ReadHoldingRegistersResponse) modbusMaster.send(request);
            } catch (ModbusTransportException e) {
                log.error( "Error reading holding registers. slaveId: " + slaveId +
                        " (startAddress: " + startAddress + ", numberOfRegisters: " + numberOfRegisters + ")", e);
                return null;
            }
        }, executor);
    }

    protected CompletableFuture<ReadInputRegistersResponse> readInputRegistersWithSlaveId(Integer slaveId, int startAddress, int numberOfRegisters) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ReadInputRegistersRequest request = new ReadInputRegistersRequest(slaveId, startAddress, numberOfRegisters);
                return (ReadInputRegistersResponse) modbusMaster.send(request);
            } catch (ModbusTransportException e) {
                log.error( "Error reading input registers. slaveId: " + slaveId +
                        " (startAddress: " + startAddress + ", numberOfRegisters: " + numberOfRegisters + ")", e);
                return null;
            }
        }, executor);
    }

    protected CompletableFuture<ReportSlaveIdResponse> reportSlaveIdWithSlaveId(Integer slaveId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ReportSlaveIdRequest request = new ReportSlaveIdRequest(slaveId);
                return (ReportSlaveIdResponse) modbusMaster.send(request);
            } catch (ModbusTransportException e) {
                log.error( "Error reporting slave ID. slaveId: " + slaveId, e);
                return null;
            }
        }, executor);
    }

    protected CompletableFuture<WriteCoilResponse> writeCoilWithSlaveId(Integer slaveId, int address, boolean value) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                WriteCoilRequest request = new WriteCoilRequest(slaveId, address, value);
                return (WriteCoilResponse) modbusMaster.send(request);
            } catch (ModbusTransportException e) {
                log.error( "Error writing coil. slaveId: " + slaveId +
                        " (address: " + address + ", value: " + value + ")", e);
                return null;
            }
        }, executor);
    }

    protected CompletableFuture<WriteCoilsResponse> writeCoilsWithSlaveId(Integer slaveId, int startAddress, boolean[] values) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                WriteCoilsRequest request = new WriteCoilsRequest(slaveId, startAddress, values);
                return (WriteCoilsResponse) modbusMaster.send(request);
            } catch (ModbusTransportException e) {
                log.error( "Error writing coils. slaveId: " + slaveId +
                        " (startAddress: " + startAddress + ", values: " + Arrays.toString(values) + ")", e);
                return null;
            }
        }, executor);
    }

    protected CompletableFuture<WriteMaskRegisterResponse> writeMaskRegisterWithSlaveId(Integer slaveId, int address, int andMask, int orMask) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                WriteMaskRegisterRequest request = new WriteMaskRegisterRequest(slaveId, address, andMask, orMask);
                return (WriteMaskRegisterResponse) modbusMaster.send(request);
            } catch (ModbusTransportException e) {
                log.error( "Error writing mask register. slaveId: " + slaveId +
                        " (address: " + address + ", andMask: " + andMask + ", orMask: " + orMask + ")", e);
                return null;
            }
        }, executor);
    }

    protected CompletableFuture<WriteRegisterResponse> writeRegisterWithSlaveId(Integer slaveId, int address, int value) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                WriteRegisterRequest request = new WriteRegisterRequest(slaveId, address, value);
                return (WriteRegisterResponse) modbusMaster.send(request);
            } catch (ModbusTransportException e) {
                log.error( "Error writing register. slaveId: " + slaveId +
                        " (address: " + address + ", value: " + value + ")", e);
                return null;
            }
        }, executor);
    }

    protected CompletableFuture<WriteRegistersResponse> writeRegistersWithSlaveId(Integer slaveId, int startAddress, short[] values) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                WriteRegistersRequest request = new WriteRegistersRequest(slaveId, startAddress, values);
                return (WriteRegistersResponse) modbusMaster.send(request);
            } catch (ModbusTransportException e) {
                log.error( "Error writing registers. slaveId: " + slaveId +
                        " (startAddress: " + startAddress + ", values: " + Arrays.toString(values) + ")", e);
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

    public void registerIntegration(String identity) {
        registeredIntegrations.add(identity);
    }

    @Deprecated
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
     * @return 锁标识（成功获取/唤醒或进入等待），null表示超时或超出等待队列容量
     */
    public String acquire(long timeout, TimeUnit unit) {
        String requestKey = generateRequestKey(); // 生成唯一请求标识
        lock.lock();
        try {
            if (currentKey == null) {
                // 直接获取锁
                currentKey = requestKey;
                log.info( "Lock acquired: " + requestKey);
                return requestKey;
            } else {
                // 检查等待队列是否未满
                if (waitQueue.size() < maxWaiters) {
                    waitQueue.add(requestKey);
                    log.info( "Enter wait queue: " + requestKey + ", queue size: " + waitQueue.size());
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
                        // 被唤醒后检查是否轮到自己
                        if (waitQueue.peek() != null && waitQueue.peek().equals(requestKey)) {
                            currentKey = requestKey;
                            waitQueue.poll();
                            log.info( "Lock acquired after waiting: " + requestKey);
                            return requestKey;
                        } else {
                            log.info( "Wait queue changed, skip acquisition: " + requestKey);
                            return null;
                        }
                    } else {
                        // 超时处理
                        waitQueue.remove(requestKey); // 从队列移除超时请求
                        // if (requestKey.equals(currentKey)) {
                        //     currentKey = null;
                        // }
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
                log.info( "Lock released: " + releaseKey);
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

        // IpParameters ipParameters = new IpParameters();
        // ipParameters.setHost(modbusInfo.ipAddress);
        // ipParameters.setPort(modbusInfo.port);

        // ModbusFactory modbusFactory = new ModbusFactory();
        // modbusMaster = modbusFactory.createTcpMaster(ipParameters, false);
        // try {
        //     modbusMaster.init();
        // } catch (ModbusInitException e) {
        //     log.error( "Failed to initialize Modbus master", e);
        // }
    }

    /**
     * @deprecated 此方法不应被调用，无法完成资源释放，更换正确方法
     * @see #closeModbus(String identity)
     */
    @Deprecated
    public void closeModbus() {
        // Empty implementation to prevent misuse
    }

    public void closeModbus(String identity) {
        // Check if identity exists in registered integrations
        if (!registeredIntegrations.contains(identity)) {
            throw new IllegalArgumentException("Identity not found: " + identity);
        }
        
        // Remove the integration from registered list
        registeredIntegrations.remove(identity);
        
        // Only close Modbus if no integrations are registered
        if (registeredIntegrations.isEmpty() && modbusMaster != null && modbusMaster.isInitialized()) {
            modbusMaster.destroy();
            executor.shutdown();
            log.info( "Modbus connection closed by " + identity);
        } else {
            log.info( "Identity removed but connection kept open: " + identity + 
                              ", remaining integrations: " + registeredIntegrations.size());
        }
    }

    public boolean isModbusOpen() {
        return modbusMaster != null && modbusMaster.isInitialized();
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
