/*
 * Copyright (c) 2026 ECAT Team
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ecat.integration.ModbusIntegration.Slave;

/**
 * Modbus 数据回调接口
 * 
 * <p>
 * 实现此接口来处理外部 Modbus Master 的读写请求。当外部设备通过 Modbus 协议
 * 访问本系统作为 Slave 提供的数据时，会调用这些回调方法。
 * 
 * <p>
 * 设计说明：
 * <ul>
 * <li>读操作：每次只读取单个寄存器/线圈（Modbus4J 内部循环调用）</li>
 * <li>写操作：区分单个写入（功能码 05/06）和批量写入（功能码 15/16）</li>
 * <li>返回 null/false 表示操作失败，返回数据/true 表示成功</li>
 * </ul>
 * 
 * <p>
 * 支持的功能码：
 * <ul>
 * <li>01 - Read Coils</li>
 * <li>02 - Read Discrete Inputs</li>
 * <li>03 - Read Holding Registers</li>
 * <li>04 - Read Input Registers</li>
 * <li>05 - Write Single Coil</li>
 * <li>06 - Write Single Register</li>
 * <li>15 (0x0F) - Write Multiple Coils</li>
 * <li>16 (0x10) - Write Multiple Registers</li>
 * </ul>
 * 
 * @author coffee
 * @see AbstractModbusDataCallback
 * @see CallbackProcessImage
 */
public interface ModbusDataCallback {

    /** 写操作成功返回值 */
    boolean SUCCESS = true;
    
    /** 写操作失败返回值 */
    boolean FAILURE = false;

    /**
     * 读取单个线圈 - 功能码 01
     * @param slaveId 从站ID
     * @param address 寄存器地址
     * @return 1字节，低位为线圈值 (0或1)，null表示失败
     */
    byte[] onReadCoil(int slaveId, int address);

    /**
     * 读取单个离散输入 - 功能码 02
     * @param slaveId 从站ID
     * @param address 寄存器地址
     * @return 1字节，低位为输入值 (0或1)，null表示失败
     */
    byte[] onReadDiscreteInput(int slaveId, int address);

    /**
     * 读取单个保持寄存器 - 功能码 03
     * @param slaveId 从站ID
     * @param address 寄存器地址
     * @return 2字节 Big-Endian，null表示失败
     */
    byte[] onReadHoldingRegister(int slaveId, int address);

    /**
     * 读取单个输入寄存器 - 功能码 04
     * @param slaveId 从站ID
     * @param address 寄存器地址
     * @return 2字节 Big-Endian，null表示失败
     */
    byte[] onReadInputRegister(int slaveId, int address);

    /**
     * 写入单个线圈 - 功能码 05
     * @param slaveId 从站ID
     * @param address 寄存器地址
     * @param value 线圈值 (true=ON, false=OFF)
     * @return true=成功, false=失败
     */
    boolean onWriteSingleCoil(int slaveId, int address, boolean value);

    /**
     * 写入单个寄存器 - 功能码 06
     * @param slaveId 从站ID
     * @param address 寄存器地址
     * @param value 2字节 Big-Endian
     * @return true=成功, false=失败
     */
    boolean onWriteSingleRegister(int slaveId, int address, byte[] value);

    /**
     * 写入多个线圈 - 功能码 15
     * @param slaveId 从站ID
     * @param startAddress 起始地址
     * @param packedBits 打包的位数据 (每字节8个线圈)
     * @param quantity 线圈数量
     * @return true=成功, false=失败
     */
    boolean onWriteMultipleCoils(int slaveId, int startAddress, byte[] packedBits, int quantity);

    /**
     * 写入多个寄存器 - 功能码 16
     * @param slaveId 从站ID
     * @param startAddress 起始地址
     * @param values 寄存器值 (每个寄存器2字节 Big-Endian)
     * @return true=成功, false=失败
     */
    boolean onWriteMultipleRegisters(int slaveId, int startAddress, byte[] values);
}
