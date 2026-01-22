package com.ecat.integration.ModbusIntegration;

/**
 * Represents the information required to connect to a Modbus TCP device.
 * This includes the IP address, port number, slave ID and protocol type(Modbus TCP or RTU over TCP).
 * 
 * @author coffee
 */
public class ModbusTcpInfo extends ModbusInfo {
    private final String ipAddress;  // IP地址
    private final Integer port;      // 端口号

    /**
     * 构造函数，默认使用 Modbus over TCP 帧格式
     * @param ipAddress IP地址
     * @param port 端口号
     * @param slaveId 从站ID
     */
    public ModbusTcpInfo(String ipAddress, Integer port, Integer slaveId) {
        this(ipAddress, port, slaveId, ModbusProtocol.TCP); // 默认使用标准 Modbus/TCP 帧格式
    }

    /**
     * 构造函数，允许指定帧格式
     * @param ipAddress IP地址
     * @param port 端口号
     * @param slaveId 从站ID
     * @param frameFormat 帧格式, ModbusProtocol.TCP 或 ModbusProtocol.RTU_OVER_TCP
     * 
     * @see ModbusProtocol
     */
    public ModbusTcpInfo(String ipAddress, Integer port, Integer slaveId, ModbusProtocol frameFormat) {
        super(slaveId, frameFormat);
        this.ipAddress = ipAddress;
        this.port = port;
    }

    @Override
    public String toString() {
        return "ModbusTcpInfo{" +
                "ipAddress='" + ipAddress + '\'' +
                ", port=" + port +
                ", slaveId=" + getSlaveId() +
                ", protocol=" + getProtocol() +
                '}';
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public Integer getPort() {
        return port;
    }
}
