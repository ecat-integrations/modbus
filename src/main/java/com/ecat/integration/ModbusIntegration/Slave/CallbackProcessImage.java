/*
 * Copyright (c) 2026 ECAT Team
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ecat.integration.ModbusIntegration.Slave;

import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
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
 * <li>本类返回结果给 Modbus4J</li>
 * </ol>
 * 
 * <p>
 * 重要说明：
 * <ul>
 * <li>数据结构：统一使用 short (16位) 作为 Modbus 寄存器基本单位</li>
 * <li>读操作：Modbus4J 对批量读取会循环调用单寄存器方法</li>
 * <li>写操作：区分 writeHoldingRegister（功能码06）和 writeHoldingRegisters（功能码16）</li>
 * <li>异常处理：回调返回 0/false 时抛出 IllegalDataAddressException</li>
 * </ul>
 * 
 * @author coffee
 * @see ModbusDataCallback
 * @see ModbusSlaveServer
 */
public class CallbackProcessImage implements ProcessImage {

    private final Log log = LogFactory.getLogger(CallbackProcessImage.class);
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
        log.info("getHoldingRegister called: slaveId=" + slaveId + ", offset=" + offset);
        if (callback == null) {
            log.warn("getHoldingRegister: callback is null");
            throw new IllegalDataAddressException();
        }
        short result = callback.onReadHoldingRegister(slaveId, offset);
        log.info("getHoldingRegister result: offset=" + offset + ", value=" + result);
        // Note: 0 is a valid register value, don't throw exception for it
        return result;
    }

    @Override
    public void setHoldingRegister(int offset, short value) {
    }

    @Override
    public void writeHoldingRegister(int offset, short value) throws IllegalDataAddressException {
        if (callback == null) {
            throw new IllegalDataAddressException();
        }
        if (!callback.onWriteSingleRegister(slaveId, offset, value)) {
            throw new IllegalDataAddressException();
        }
    }

    @Override
    public void writeHoldingRegisters(int offset, short[] values) throws IllegalDataAddressException {
        if (callback == null) {
            throw new IllegalDataAddressException();
        }
        if (!callback.onWriteMultipleRegisters(slaveId, offset, values)) {
            throw new IllegalDataAddressException();
        }
    }

    @Override
    public short getInputRegister(int offset) throws IllegalDataAddressException {
        if (callback == null) {
            throw new IllegalDataAddressException();
        }
        short result = callback.onReadInputRegister(slaveId, offset);
        // Note: 0 is a valid register value, don't throw exception for it
        return result;
    }

    @Override
    public void setInputRegister(int offset, short value) {
    }

    @Override
    public boolean getCoil(int offset) throws IllegalDataAddressException {
        if (callback == null) {
            throw new IllegalDataAddressException();
        }
        return callback.onReadCoil(slaveId, offset);
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
        return callback.onReadDiscreteInput(slaveId, offset);
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
