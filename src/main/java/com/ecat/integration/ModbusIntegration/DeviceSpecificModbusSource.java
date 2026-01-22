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
    
    /**
     * 创建设备特定的ModbusSource
     * @param delegate 共享的底层ModbusSource
     * @param deviceModbusInfo 当前设备的ModbusInfo
     */
    public DeviceSpecificModbusSource(ModbusSource delegate, ModbusInfo deviceModbusInfo) {
        // 调用父类protected构造函数，传入一个临时的ModbusInfo
        super(delegate.getModbusInfo(), delegate.getMaxWaiters(), delegate.getWaitTimeoutMs(), true);
        
        this.delegate = delegate;
        this.deviceModbusInfo = deviceModbusInfo;
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
    
    @Override
    public boolean release(String releaseKey) {
        return delegate.release(releaseKey);
    }
    
    @Override
    public int getWaitingCount() {
        return delegate.getWaitingCount();
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
        // 不关闭，因为这是共享资源
        // 只有在ModbusIntegration.onRelease()时才真正关闭
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
