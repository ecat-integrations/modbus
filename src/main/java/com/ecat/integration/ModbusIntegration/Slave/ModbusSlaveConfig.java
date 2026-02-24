/*
 * Copyright (c) 2026 ECAT Team
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ecat.integration.ModbusIntegration.Slave;

import com.ecat.integration.ModbusIntegration.ModbusProtocol;
import lombok.Getter;
import lombok.Setter;

/**
 * Modbus Slave 配置基类
 * 
 * <p>
 * 定义所有 Slave 配置的公共属性，包括从站ID、协议类型和数据回调。
 * 子类扩展特定传输协议的配置参数。
 * 
 * <p>
 * 子类实现：
 * <ul>
 * <li>{@link ModbusTcpSlaveConfig} - TCP 连接配置</li>
 * <li>{@link ModbusSerialSlaveConfig} - Serial RTU 连接配置</li>
 * </ul>
 * 
 * @author coffee
 * @see ModbusTcpSlaveConfig
 * @see ModbusSerialSlaveConfig
 */
@Getter
@Setter
public abstract class ModbusSlaveConfig {
    protected final int slaveId;
    protected final ModbusProtocol protocol;
    protected ModbusDataCallback callback;

    protected ModbusSlaveConfig(int slaveId, ModbusProtocol protocol) {
        this.slaveId = slaveId;
        this.protocol = protocol;
    }

    public abstract String getConnectionIdentity();
}
