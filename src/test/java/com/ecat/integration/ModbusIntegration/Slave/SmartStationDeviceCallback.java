/*
 * Copyright (c) 2026 ECAT Team
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ecat.integration.ModbusIntegration.Slave;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 智慧站房设备模拟器 - 用于测试 Modbus Slave 功能
 * 
 * <p>
 * 基于 protocol.md 协议文档实现的设备模拟器，提供完整的 Modbus 数据模型支持：
 * <ul>
 * <li>设备默认地址: 36</li>
 * <li>寄存器地址范围: 0-154</li>
 * <li>数据类型: unsigned short, short, unsigned long</li>
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
 * <li>15 - Write Multiple Coils</li>
 * <li>16 - Write Multiple Registers</li>
 * </ul>
 * 
 * <p>
 * 内置读写计数器，可用于验证测试覆盖率。
 * 
 * @author coffee
 * @see AbstractModbusDataCallback
 */
public class SmartStationDeviceCallback extends AbstractModbusDataCallback {

    private final short[] registers = new short[200];
    private final AtomicInteger readCount = new AtomicInteger(0);
    private final AtomicInteger writeCount = new AtomicInteger(0);

    public SmartStationDeviceCallback() {
        initDefaultValues();
    }

    private void initDefaultValues() {
        registers[0] = 220;
        registers[1] = 218;
        registers[2] = 221;
        registers[3] = 15;
        registers[4] = 16;
        registers[5] = 14;
        registers[6] = 0x12;
        registers[7] = 0x3456;
        registers[8] = 30;
        registers[9] = 0;
        registers[10] = 0;
        registers[11] = 35;
        registers[12] = 42;
        registers[13] = 8;
        registers[14] = 0;
        for (int i = 15; i <= 32; i++) {
            registers[i] = (short) (10 + i);
        }
        registers[33] = 1013;
        registers[34] = 25;
        registers[35] = 60;
        registers[36] = 0;
        for (int i = 37; i <= 154; i++) {
            registers[i] = 0;
        }
        registers[41] = 400;
        registers[70] = 22;
        registers[71] = 0;
        registers[74] = 0;
        registers[75] = 0;
        long timestamp = System.currentTimeMillis() / 1000;
        registers[127] = (short) ((timestamp >> 16) & 0xFFFF);
        registers[128] = (short) (timestamp & 0xFFFF);
    }

    @Override
    public byte[] onReadHoldingRegister(int slaveId, int address) {
        readCount.incrementAndGet();
        
        if (address < 0 || address >= registers.length) {
            return null;
        }
        
        short value = registers[address];
        return new byte[] {
            (byte) ((value >> 8) & 0xFF),
            (byte) (value & 0xFF)
        };
    }

    @Override
    public byte[] onReadInputRegister(int slaveId, int address) {
        return onReadHoldingRegister(slaveId, address);
    }

    @Override
    public byte[] onReadCoil(int slaveId, int address) {
        readCount.incrementAndGet();
        
        if (address < 0 || address >= registers.length) {
            return null;
        }
        
        boolean value = (registers[address] & 0x01) != 0;
        return new byte[] { (byte) (value ? 0x01 : 0x00) };
    }

    @Override
    public byte[] onReadDiscreteInput(int slaveId, int address) {
        return onReadCoil(slaveId, address);
    }

    @Override
    public boolean onWriteSingleRegister(int slaveId, int address, byte[] value) {
        writeCount.incrementAndGet();
        
        if (address < 0 || address >= registers.length || value.length < 2) {
            return FAILURE;
        }
        
        short val = (short) (((value[0] & 0xFF) << 8) | (value[1] & 0xFF));
        registers[address] = val;
        
        handleSpecialWrite(address, val);
        
        return SUCCESS;
    }

    @Override
    public boolean onWriteMultipleRegisters(int slaveId, int startAddress, byte[] values) {
        writeCount.incrementAndGet();
        
        int quantity = values.length / 2;
        
        if (startAddress < 0 || startAddress + quantity > registers.length) {
            return FAILURE;
        }
        
        for (int i = 0; i < quantity; i++) {
            short val = (short) (((values[i * 2] & 0xFF) << 8) | (values[i * 2 + 1] & 0xFF));
            registers[startAddress + i] = val;
            handleSpecialWrite(startAddress + i, val);
        }
        
        return SUCCESS;
    }

    @Override
    public boolean onWriteSingleCoil(int slaveId, int address, boolean value) {
        writeCount.incrementAndGet();
        
        if (address < 0 || address >= registers.length) {
            return FAILURE;
        }
        
        if (value) {
            registers[address] |= 0x01;
        } else {
            registers[address] &= 0xFFFE;
        }
        
        return SUCCESS;
    }

    @Override
    public boolean onWriteMultipleCoils(int slaveId, int startAddress, byte[] packedBits, int quantity) {
        writeCount.incrementAndGet();
        
        if (startAddress < 0 || startAddress + quantity > registers.length) {
            return FAILURE;
        }
        
        for (int i = 0; i < quantity; i++) {
            boolean value = (packedBits[i / 8] & (1 << (i % 8))) != 0;
            if (value) {
                registers[startAddress + i] |= 0x01;
            } else {
                registers[startAddress + i] &= 0xFFFE;
            }
        }
        
        return SUCCESS;
    }

    private void handleSpecialWrite(int address, short value) {
        if (address == 9 && value == 1) {
            registers[6] = 0;
            registers[7] = 0;
            registers[9] = 0;
        }
        if (address == 28 && value == 1) {
            registers[21] = 0;
            registers[22] = 0;
            registers[23] = 0;
            registers[24] = 0;
            registers[25] = 0;
            registers[26] = 0;
            registers[27] = 0;
            registers[28] = 0;
        }
    }

    public void setRegister(int address, short value) {
        if (address >= 0 && address < registers.length) {
            registers[address] = value;
        }
    }

    public short getRegister(int address) {
        if (address >= 0 && address < registers.length) {
            return registers[address];
        }
        return 0;
    }

    public void setUnsignedLong(int address, long value) {
        if (address >= 0 && address + 1 < registers.length) {
            registers[address] = (short) ((value >> 16) & 0xFFFF);
            registers[address + 1] = (short) (value & 0xFFFF);
        }
    }

    public long getUnsignedLong(int address) {
        if (address >= 0 && address + 1 < registers.length) {
            int high = registers[address] & 0xFFFF;
            int low = registers[address + 1] & 0xFFFF;
            return ((long) high << 16) | low;
        }
        return 0;
    }

    public int getReadCount() {
        return readCount.get();
    }

    public int getWriteCount() {
        return writeCount.get();
    }

    public void resetCounters() {
        readCount.set(0);
        writeCount.set(0);
    }
}
