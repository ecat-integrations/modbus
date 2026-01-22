package com.ecat.integration.ModbusIntegration;

/**
 * Modbus协议类型枚举
 * 
 * @author coffee
 */
public enum ModbusProtocol {
    TCP,    // Modbus TCP
    SERIAL,  // Modbus RTU/ASCII（串行）
    RTU_OVER_TCP // Modbus RTU over TCP
}
