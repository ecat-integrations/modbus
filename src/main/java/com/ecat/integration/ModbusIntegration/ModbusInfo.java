package com.ecat.integration.ModbusIntegration;

/**
 * Modbus设备信息抽象基类
 * 定义所有Modbus连接的公共属性和行为
 */
public abstract class ModbusInfo {
    protected final Integer slaveId;       // 从站ID
    protected final ModbusProtocol protocol; // 协议类型

    public ModbusInfo(Integer slaveId, ModbusProtocol protocol) {
        this.slaveId = slaveId;
        this.protocol = protocol;
    }

    public Integer getSlaveId() {
        return slaveId;
    }

    public ModbusProtocol getProtocol() {
        return protocol;
    }

    // 生成字符串
    public abstract String toString();
}