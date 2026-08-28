package com.ecat.integration.ModbusIntegration;

import com.serotonin.modbus4j.msg.*;
import lombok.Getter;

import java.util.concurrent.CompletableFuture;

/**
 * 设备特定的ModbusSource，用于解决多设备共享连接时的slaveId冲突问题
 * 
 * 每个DeviceSpecificModbusSource实例对应一个具体的设备（具有特定的slaveId），
 * 但底层共享同一个ModbusMaster和锁机制。
 * 
 * @author coffee
 */
public class DeviceSpecificModbusSource extends ModbusSource {

    private final ModbusSource delegate;
    @Getter
    private final ModbusInfo deviceModbusInfo;
    private final String deviceIdentity;

    /**
     * 创建设备特定的ModbusSource
     * @param delegate 共享的底层ModbusSource
     * @param deviceModbusInfo 当前设备的ModbusInfo
     * @param deviceIdentity 设备唯一标识（用于 closeModbus 时的引用计数）
     */
    public DeviceSpecificModbusSource(ModbusSource delegate, ModbusInfo deviceModbusInfo, String deviceIdentity) {
        // 调用父类protected构造函数，skipOpen=true（由 delegate 管理 master），
        // delegateMode=true（不创建自己的 executor，所有操作委托给 delegate）
        super(delegate.getModbusInfo(), delegate.getMaxWaiters(), delegate.getWaitTimeoutMs(), true, true);

        this.delegate = delegate;
        this.deviceModbusInfo = deviceModbusInfo;
        this.deviceIdentity = deviceIdentity;
    }

    /**
     * @deprecated 使用三参构造函数 {@link #DeviceSpecificModbusSource(ModbusSource, ModbusInfo, String)} 代替
     */
    @Deprecated
    public DeviceSpecificModbusSource(ModbusSource delegate, ModbusInfo deviceModbusInfo) {
        this(delegate, deviceModbusInfo, null);
    }

    public Integer getDeviceSlaveId() {
        return deviceModbusInfo.getSlaveId();
    }
    
    // 委托锁管理方法
    @Override
    public String acquire() {
        return delegate.acquire();
    }
    
    @Override
    public String acquire(long timeout, java.util.concurrent.TimeUnit unit) {
        return delegate.acquire(timeout, unit);
    }

    /**
     * 非阻塞获取锁（轮询专用，E2/R3「过期即弃」）：委托共享 delegate（本类 delegateMode
     * 不持有锁状态机，与 {@link #acquire()} 同一委托边界）。语义见
     * {@link ModbusSource#tryAcquire()}。
     */
    @Override
    public String tryAcquire() {
        return delegate.tryAcquire();
    }

    /** 累计轮询锁忙放弃计数（委托 delegate 的记账，跨设备共享同一把源锁故同一计数）。 */
    @Override
    public long getLockBusySkipCount() {
        return delegate.getLockBusySkipCount();
    }

    @Override
    public boolean release(String releaseKey) {
        return delegate.release(releaseKey);
    }
    
    @Override
    public int getWaitingCount() {
        return delegate.getWaitingCount();
    }

    /**
     * 挂死传输强拆委托给共享 delegate（master/传输资源归 delegate 所有，本类 delegateMode
     * 不持有）。Q-1/A2：DeviceSpecificModbusSource 与 delegate 共用同一把源锁，事务硬超时
     * 路径经本类调用时必须落到真实持有传输的 delegate 上。
     */
    @Override
    public void forceRecoverTransport(String reason) {
        delegate.forceRecoverTransport(reason);
    }
    
    // 直接委托给delegate的带slaveId方法
    @Override
    public CompletableFuture<ReadCoilsResponse> readCoils(int startAddress, int numberOfBits) {
        return delegate.readCoilsWithSlaveId(deviceModbusInfo.getSlaveId(), startAddress, numberOfBits);
    }
    
    @Override
    public CompletableFuture<ReadDiscreteInputsResponse> readDiscreteInputs(int startAddress, int numberOfBits) {
        return delegate.readDiscreteInputsWithSlaveId(deviceModbusInfo.getSlaveId(), startAddress, numberOfBits);
    }
    
    @Override
    public CompletableFuture<ReadExceptionStatusResponse> readExceptionStatus() {
        return delegate.readExceptionStatusWithSlaveId(deviceModbusInfo.getSlaveId());
    }
    
    @Override
    public CompletableFuture<ReadHoldingRegistersResponse> readHoldingRegisters(int startAddress, int numberOfRegisters) {
        return delegate.readHoldingRegistersWithSlaveId(deviceModbusInfo.getSlaveId(), startAddress, numberOfRegisters);
    }
    
    @Override
    public CompletableFuture<ReadInputRegistersResponse> readInputRegisters(int startAddress, int numberOfRegisters) {
        return delegate.readInputRegistersWithSlaveId(deviceModbusInfo.getSlaveId(), startAddress, numberOfRegisters);
    }
    
    @Override
    public CompletableFuture<ReportSlaveIdResponse> reportSlaveId() {
        return delegate.reportSlaveIdWithSlaveId(deviceModbusInfo.getSlaveId());
    }
    
    @Override
    public CompletableFuture<WriteCoilResponse> writeCoil(int address, boolean value) {
        return delegate.writeCoilWithSlaveId(deviceModbusInfo.getSlaveId(), address, value);
    }
    
    @Override
    public CompletableFuture<WriteCoilsResponse> writeCoils(int startAddress, boolean[] values) {
        return delegate.writeCoilsWithSlaveId(deviceModbusInfo.getSlaveId(), startAddress, values);
    }
    
    @Override
    public CompletableFuture<WriteMaskRegisterResponse> writeMaskRegister(int address, int andMask, int orMask) {
        return delegate.writeMaskRegisterWithSlaveId(deviceModbusInfo.getSlaveId(), address, andMask, orMask);
    }
    
    @Override
    public CompletableFuture<WriteRegisterResponse> writeRegister(int address, int value) {
        return delegate.writeRegisterWithSlaveId(deviceModbusInfo.getSlaveId(), address, value);
    }
    
    @Override
    public CompletableFuture<WriteRegistersResponse> writeRegisters(int startAddress, short[] values) {
        return delegate.writeRegistersWithSlaveId(deviceModbusInfo.getSlaveId(), startAddress, values);
    }
    
    // 委托其他必要的方法
    @Override
    public void registerIntegration(String identity) {
        delegate.registerIntegration(identity);
    }
    
    @Override
    public void removeIntegration(String identity) {
        delegate.removeIntegration(identity);
    }
    
    @Override
    public boolean isModbusOpen() {
        return delegate.isModbusOpen();
    }
    
    @Override
    public void closeModbus() {
        if (deviceIdentity != null) {
            delegate.closeModbus(deviceIdentity);
        }
        // deviceIdentity == null 时保持旧行为（向后兼容）
    }
    
    @Override
    public int getMaxWaiters() {
        return delegate.getMaxWaiters();
    }
    
    @Override
    public int getWaitTimeoutMs() {
        return delegate.getWaitTimeoutMs();
    }
    
    @Override
    public ModbusInfo getModbusInfo() {
        return delegate.getModbusInfo();
    }
}
