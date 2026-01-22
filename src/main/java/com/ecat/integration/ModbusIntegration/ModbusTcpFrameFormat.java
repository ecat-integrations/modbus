package com.ecat.integration.ModbusIntegration;

/**
 * Modbus TCP 帧格式
 * 
 * @author coffee
 */
public enum ModbusTcpFrameFormat {
    /**
     * 标准 Modbus/TCP（MBAP 头）
     */
    MODBUS_TCP,
    /**
     * 把 RTU 帧原封不动塞进 TCP，俗称 RTU over TCP
     */
    MODBUS_RTU_OVER_TCP
}
