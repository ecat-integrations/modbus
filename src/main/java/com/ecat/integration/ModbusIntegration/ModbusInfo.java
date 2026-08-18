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

    /**
     * 请求级 I/O 超时（毫秒）：{@code master.setTimeout} 的取值（RTU=串口读超时，TCP=事务超时），
     * 单次 {@code master.send} 的时延由它（含内置重试）约束。
     * 消费方据此派生「同步等待单次读 future 完成」的有界上限（103000：无界 join/get 在
     * 执行器/车道饥饿时无限钉死调用线程，须改为有界等待 + 超时失败路径）。
     */
    public abstract int getRequestTimeoutMs();

    // 生成字符串
    public abstract String toString();
}