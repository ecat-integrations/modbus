/*
 * Copyright (c) 2026 ECAT Team
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ecat.integration.ModbusIntegration.Slave;

import com.ecat.integration.ModbusIntegration.ModbusProtocol;
import lombok.Getter;

/**
 * Modbus TCP Slave 配置类
 * 
 * <p>
 * 用于配置基于 TCP/IP 的 Modbus Slave 服务，支持标准 Modbus TCP
 * 和 RTU over TCP 两种协议模式。
 * 
 * <p>
 * 配置参数：
 * <ul>
 * <li>slaveId - 从站ID（1-247）</li>
 * <li>ipAddress - 监听地址（如 "0.0.0.0" 监听所有网卡）</li>
 * <li>port - 监听端口（Modbus TCP 默认 502）</li>
 * <li>protocol - TCP 或 RTU_OVER_TCP</li>
 * </ul>
 * 
 * <p>
 * 使用示例：
 * <pre>{@code
 * // 标准 Modbus TCP
 * ModbusTcpSlaveConfig config = new ModbusTcpSlaveConfig(1, "0.0.0.0", 5020);
 * 
 * // RTU over TCP
 * ModbusTcpSlaveConfig config = new ModbusTcpSlaveConfig(1, "0.0.0.0", 5020, ModbusProtocol.RTU_OVER_TCP);
 * 
 * config.setCallback(myCallback);
 * }</pre>
 * 
 * @author coffee
 * @see ModbusSlaveConfig
 * @see ModbusSerialSlaveConfig
 */
@Getter
public class ModbusTcpSlaveConfig extends ModbusSlaveConfig {
    private final String ipAddress;
    private final int port;

    public ModbusTcpSlaveConfig(int slaveId, String ipAddress, int port) {
        this(slaveId, ipAddress, port, ModbusProtocol.TCP);
    }

    public ModbusTcpSlaveConfig(int slaveId, String ipAddress, int port, ModbusProtocol protocol) {
        super(slaveId, protocol);
        if (protocol != ModbusProtocol.TCP && protocol != ModbusProtocol.RTU_OVER_TCP) {
            throw new IllegalArgumentException("TCP Slave only supports TCP or RTU_OVER_TCP protocol");
        }
        this.ipAddress = ipAddress;
        this.port = port;
    }

    @Override
    public String getConnectionIdentity() {
        return ipAddress + ":" + port;
    }

    @Override
    public String toString() {
        return "ModbusTcpSlaveConfig{" +
                "ipAddress='" + ipAddress + '\'' +
                ", port=" + port +
                ", slaveId=" + slaveId +
                ", protocol=" + protocol +
                '}';
    }
}
