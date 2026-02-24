/*
 * Copyright (c) 2026 ECAT Team
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ecat.integration.ModbusIntegration.Slave;

/**
 * ModbusDataCallback 的默认抽象实现
 * 
 * <p>
 * 所有方法默认返回 FAILURE 或 null，子类只需重写需要支持的方法。
 * 这简化了回调接口的实现，开发者可以只关注需要的功能码。
 * 
 * <p>
 * 使用示例：
 * <pre>{@code
 * ModbusDataCallback callback = new AbstractModbusDataCallback() {
 *     @Override
 *     public byte[] onReadHoldingRegister(int slaveId, int address) {
 *         int value = getDeviceValue(address);
 *         return new byte[] {
 *             (byte) ((value >> 8) & 0xFF),
 *             (byte) (value & 0xFF)
 *         };
 *     }
 * };
 * }</pre>
 * 
 * @author coffee
 * @see ModbusDataCallback
 */
public abstract class AbstractModbusDataCallback implements ModbusDataCallback {

    @Override
    public byte[] onReadCoil(int slaveId, int address) {
        return null;
    }

    @Override
    public byte[] onReadDiscreteInput(int slaveId, int address) {
        return null;
    }

    @Override
    public byte[] onReadHoldingRegister(int slaveId, int address) {
        return null;
    }

    @Override
    public byte[] onReadInputRegister(int slaveId, int address) {
        return null;
    }

    @Override
    public boolean onWriteSingleCoil(int slaveId, int address, boolean value) {
        return FAILURE;
    }

    @Override
    public boolean onWriteSingleRegister(int slaveId, int address, byte[] value) {
        return FAILURE;
    }

    @Override
    public boolean onWriteMultipleCoils(int slaveId, int startAddress, byte[] packedBits, int quantity) {
        return FAILURE;
    }

    @Override
    public boolean onWriteMultipleRegisters(int slaveId, int startAddress, byte[] values) {
        return FAILURE;
    }
}
