/**
 * Represents the information required to connect to a Modbus TCP device.
 * This includes the IP address, port number, and slave ID.
 * 
 * @author coffee
 */
package com.ecat.integration.ModbusIntegration;

public class ModbusInfo1 {
    // IP地址
    String ipAddress;
    // 端口号
    Integer port;
    // 从站ID
    Integer slaveId;

    public ModbusInfo1(String ipAddress, Integer port, Integer slaveId) {
        this.ipAddress = ipAddress;
        this.port = port;
        this.slaveId = slaveId;
    }

    // 生成字符串
    public String toString() {
        return "ModbusInfo{" +
                "ipAddress='" + ipAddress + '\'' +
                ", port=" + port +
                ", slaveId=" + slaveId +
                '}';
    }
}    
