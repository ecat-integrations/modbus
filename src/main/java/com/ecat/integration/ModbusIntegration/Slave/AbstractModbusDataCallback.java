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
 * 所有方法默认返回 FAILURE 或 0，子类只需重写需要支持的方法。
 * 这简化了回调接口的实现，开发者可以只关注需要的功能码。
 *
 * <p>
 * 使用示例：
 * <pre>{@code
 * ModbusDataCallback callback = new AbstractModbusDataCallback() {
 *     @Override
 *     public short onReadHoldingRegister(int slaveId, int address) {
 *         return getDeviceValue(address);
 *     }
 * };
 * }</pre>
 *
 * <p><b>写回调阻塞预算声明（07 号 §11.3 C 案）</b>：写回调（onWriteSingleRegister /
 * onWriteMultipleRegisters 等）在 modbus4j slave 线程上同步执行——若实现内部等待设备写 IO
 * （属性命令 future 等），等待时长<b>必须有限</b>且不超过 {@link #WRITE_CALLBACK_BUDGET_GUIDE_MS}，
 * 超时须返回 FAILURE 让 modbus4j 回异常响应（master 侧可见失败而非无响应挂死）。禁止无超时
 * 的 {@code future.get()}。预算派生：ModbusSource 总线锁等待（Const.DEFAULT_WAIT_TIMEOUT_MS=2000）
 * + 目标设备事务超时×(modbus4j 内置重试+1)（master.setTimeout 默认 2000、ModbusMaster 默认
 * retries=2 → 6000），合计 8000ms。样板实现见 leitechina slave Callback（经 CoreExecutionApi
 * submitSync 通道，预算与本常量同源）。
 *
 * @author coffee
 * @see ModbusDataCallback
 */
public abstract class AbstractModbusDataCallback implements ModbusDataCallback {

    /**
     * Slave 写回调阻塞预算指导值（毫秒）：实现方在写回调内同步等待设备 IO 的时长上限
     * （见类 Javadoc「写回调阻塞预算声明」）。文档/常量级声明，框架不强制——由实现方
     * 对齐（leitechina Callback.WRITE_COMMAND_BUDGET_MS 与此同源）。
     */
    public static final long WRITE_CALLBACK_BUDGET_GUIDE_MS = 8000L;

    @Override
    public boolean onReadCoil(int slaveId, int address) {
        return false;
    }

    @Override
    public boolean onReadDiscreteInput(int slaveId, int address) {
        return false;
    }

    @Override
    public short onReadHoldingRegister(int slaveId, int address) {
        return 0;
    }

    @Override
    public short onReadInputRegister(int slaveId, int address) {
        return 0;
    }

    @Override
    public boolean onWriteSingleCoil(int slaveId, int address, boolean value) {
        return FAILURE;
    }

    @Override
    public boolean onWriteSingleRegister(int slaveId, int address, short value) {
        return FAILURE;
    }

    @Override
    public boolean onWriteMultipleCoils(int slaveId, int startAddress, byte[] packedBits, int quantity) {
        return FAILURE;
    }

    @Override
    public boolean onWriteMultipleRegisters(int slaveId, int startAddress, short[] values) {
        return FAILURE;
    }
}
