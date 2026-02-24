/*
 * Copyright (c) 2026 ECAT Team
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ecat.integration.ModbusIntegration.Slave;

import com.serotonin.modbus4j.ProcessImage;
import com.serotonin.modbus4j.exception.IllegalDataAddressException;

/**
 * ProcessImage 回调实现类
 * 
 * <p>
 * 实现 Modbus4J 的 {@link ProcessImage} 接口，将 Modbus 请求转发给
 * {@link ModbusDataCallback} 进行处理。这是 Modbus Slave 服务的核心组件，
 * 负责在 Modbus4J 框架和用户回调之间建立桥梁。
 * 
 * <p>
 * 工作流程：
 * <ol>
 * <li>外部 Master 发起 Modbus 请求</li>
 * <li>Modbus4J Slave 接收请求</li>
 * <li>Modbus4J 调用 ProcessImage 的对应方法</li>
 * <li>本类将调用转发给 ModbusDataCallback</li>
 * <li>用户回调返回数据或处理结果</li>
 * <li>本类解析结果并返回给 Modbus4J</li>
 * </ol>
 * 
 * <p>
 * 重要说明：
 * <ul>
 * <li>读操作：Modbus4J 对批量读取会循环调用单寄存器方法</li>
 * <li>写操作：区分 writeHoldingRegister（功能码06）和 writeHoldingRegisters（功能码16）</li>
 * <li>异常处理：回调返回 null/false 时抛出 IllegalDataAddressException</li>
 * </ul>
 * 
 * @author coffee
 * @see ModbusDataCallback
 * @see ModbusSlaveServer
 */
public class CallbackProcessImage implements ProcessImage {

    private final int slaveId;
    private ModbusDataCallback callback;

    public CallbackProcessImage(int slaveId, ModbusDataCallback callback) {
        this.slaveId = slaveId;
        this.callback = callback;
    }

    public void setCallback(ModbusDataCallback callback) {
        this.callback = callback;
    }

    @Override
    public int getSlaveId() {
        return slaveId;
    }

    @Override
    public short getHoldingRegister(int offset) throws IllegalDataAddressException {
        if (callback == null) {
            throw new IllegalDataAddressException();
        }
        byte[] result = callback.onReadHoldingRegister(slaveId, offset);
        if (result == null || result.length < 2) {
            throw new IllegalDataAddressException();
        }
        return (short) (((result[0] & 0xFF) << 8) | (result[1] & 0xFF));
    }

    @Override
    public void setHoldingRegister(int offset, short value) {
    }

    @Override
    public void writeHoldingRegister(int offset, short value) throws IllegalDataAddressException {
        if (callback == null) {
            throw new IllegalDataAddressException();
        }
        byte[] writeData = new byte[] {
            (byte) ((value >> 8) & 0xFF),
            (byte) (value & 0xFF)
        };
        if (!callback.onWriteSingleRegister(slaveId, offset, writeData)) {
            throw new IllegalDataAddressException();
        }
    }

    @Override
    public void writeHoldingRegisters(int offset, short[] values) throws IllegalDataAddressException {
        if (callback == null) {
            throw new IllegalDataAddressException();
        }
        int quantity = values.length;
        byte[] writeData = new byte[quantity * 2];
        for (int i = 0; i < quantity; i++) {
            writeData[i * 2] = (byte) ((values[i] >> 8) & 0xFF);
            writeData[i * 2 + 1] = (byte) (values[i] & 0xFF);
        }
        if (!callback.onWriteMultipleRegisters(slaveId, offset, writeData)) {
            throw new IllegalDataAddressException();
        }
    }

    @Override
    public short getInputRegister(int offset) throws IllegalDataAddressException {
        if (callback == null) {
            throw new IllegalDataAddressException();
        }
        byte[] result = callback.onReadInputRegister(slaveId, offset);
        if (result == null || result.length < 2) {
            throw new IllegalDataAddressException();
        }
        return (short) (((result[0] & 0xFF) << 8) | (result[1] & 0xFF));
    }

    @Override
    public void setInputRegister(int offset, short value) {
    }

    @Override
    public boolean getCoil(int offset) throws IllegalDataAddressException {
        if (callback == null) {
            throw new IllegalDataAddressException();
        }
        byte[] result = callback.onReadCoil(slaveId, offset);
        if (result == null || result.length < 1) {
            throw new IllegalDataAddressException();
        }
        return (result[0] & 0x01) != 0;
    }

    @Override
    public void setCoil(int offset, boolean value) {
    }

    @Override
    public void writeCoil(int offset, boolean value) throws IllegalDataAddressException {
        if (callback == null) {
            throw new IllegalDataAddressException();
        }
        if (!callback.onWriteSingleCoil(slaveId, offset, value)) {
            throw new IllegalDataAddressException();
        }
    }

    @Override
    public void writeCoils(int offset, boolean[] values) throws IllegalDataAddressException {
        if (callback == null) {
            throw new IllegalDataAddressException();
        }
        int quantity = values.length;
        byte[] packedBits = new byte[(quantity + 7) / 8];
        for (int i = 0; i < quantity; i++) {
            if (values[i]) {
                packedBits[i / 8] |= (1 << (i % 8));
            }
        }
        if (!callback.onWriteMultipleCoils(slaveId, offset, packedBits, quantity)) {
            throw new IllegalDataAddressException();
        }
    }

    @Override
    public boolean getInput(int offset) throws IllegalDataAddressException {
        if (callback == null) {
            throw new IllegalDataAddressException();
        }
        byte[] result = callback.onReadDiscreteInput(slaveId, offset);
        if (result == null || result.length < 1) {
            throw new IllegalDataAddressException();
        }
        return (result[0] & 0x01) != 0;
    }

    @Override
    public void setInput(int offset, boolean value) {
    }

    @Override
    public byte getExceptionStatus() {
        return 0;
    }

    @Override
    public byte[] getReportSlaveIdData() {
        return new byte[0];
    }
}
