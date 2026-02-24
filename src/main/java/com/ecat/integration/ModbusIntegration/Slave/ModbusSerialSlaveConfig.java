/*
 * Copyright (c) 2026 ECAT Team
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ecat.integration.ModbusIntegration.Slave;

import com.ecat.integration.ModbusIntegration.ModbusProtocol;
import lombok.Getter;

/**
 * Modbus Serial RTU Slave 配置类
 * 
 * <p>
 * 用于配置基于串行通信（RS-232/RS-485）的 Modbus RTU Slave 服务。
 * 
 * <p>
 * 配置参数：
 * <ul>
 * <li>slaveId - 从站ID（1-247）</li>
 * <li>portName - 串口设备名（如 "/dev/ttyUSB0" 或 "COM3"）</li>
 * <li>baudRate - 波特率（如 9600, 19200, 115200）</li>
 * <li>dataBits - 数据位（5-8，通常为 8）</li>
 * <li>stopBits - 停止位（1 或 2，使用常量 ONE_STOP_BIT 或 TWO_STOP_BITS）</li>
 * <li>parity - 校验位（无校验、奇校验、偶校验）</li>
 * </ul>
 * 
 * <p>
 * 校验位常量：
 * <ul>
 * <li>{@link #NO_PARITY} - 无校验（0）</li>
 * <li>{@link #ODD_PARITY} - 奇校验（1）</li>
 * <li>{@link #EVEN_PARITY} - 偶校验（2）</li>
 * </ul>
 * 
 * <p>
 * 停止位常量：
 * <ul>
 * <li>{@link #ONE_STOP_BIT} - 1 位停止位（值 1）</li>
 * <li>{@link #TWO_STOP_BITS} - 2 位停止位（值 3）</li>
 * </ul>
 * 
 * <p>
 * 使用示例：
 * <pre>{@code
 * ModbusSerialSlaveConfig config = new ModbusSerialSlaveConfig(
 *     36,                                              // slaveId
 *     "/dev/ttyUSB0",                                  // portName
 *     9600,                                            // baudRate
 *     8,                                               // dataBits
 *     ModbusSerialSlaveConfig.ONE_STOP_BIT,           // stopBits
 *     ModbusSerialSlaveConfig.NO_PARITY               // parity
 * );
 * config.setCallback(myCallback);
 * }</pre>
 * 
 * @author coffee
 * @see ModbusSlaveConfig
 * @see ModbusTcpSlaveConfig
 */
@Getter
public class ModbusSerialSlaveConfig extends ModbusSlaveConfig {
    
    /** 无校验 */
    public static final int NO_PARITY = 0;
    
    /** 奇校验 */
    public static final int ODD_PARITY = 1;
    
    /** 偶校验 */
    public static final int EVEN_PARITY = 2;
    
    /** 1 位停止位 */
    public static final int ONE_STOP_BIT = 1;
    
    /** 2 位停止位 */
    public static final int TWO_STOP_BITS = 3;

    private final String portName;
    private final int baudRate;
    private final int dataBits;
    private final int stopBits;
    private final int parity;

    public ModbusSerialSlaveConfig(int slaveId, String portName, int baudRate, 
                                   int dataBits, int stopBits, int parity) {
        super(slaveId, ModbusProtocol.SERIAL);
        validateParameters(portName, baudRate, dataBits, stopBits, parity);
        this.portName = portName;
        this.baudRate = baudRate;
        this.dataBits = dataBits;
        this.stopBits = stopBits;
        this.parity = parity;
    }

    private void validateParameters(String portName, int baudRate, int dataBits, 
                                    int stopBits, int parity) {
        if (portName == null || portName.trim().isEmpty()) {
            throw new IllegalArgumentException("Port name cannot be empty");
        }
        if (baudRate <= 0) {
            throw new IllegalArgumentException("Invalid baud rate");
        }
        if (dataBits < 5 || dataBits > 8) {
            throw new IllegalArgumentException("Data bits must be 5-8");
        }
        if (stopBits != ONE_STOP_BIT && stopBits != TWO_STOP_BITS) {
            throw new IllegalArgumentException("Stop bits must be 1 or 3");
        }
        if (parity < NO_PARITY || parity > EVEN_PARITY) {
            throw new IllegalArgumentException("Parity must be 0, 1, or 2");
        }
    }

    @Override
    public String getConnectionIdentity() {
        return portName;
    }

    @Override
    public String toString() {
        return "ModbusSerialSlaveConfig{" +
                "portName='" + portName + '\'' +
                ", baudRate=" + baudRate +
                ", dataBits=" + dataBits +
                ", stopBits=" + stopBits +
                ", parity=" + parity +
                ", slaveId=" + slaveId +
                '}';
    }
}
