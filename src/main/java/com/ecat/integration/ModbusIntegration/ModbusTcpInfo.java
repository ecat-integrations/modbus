package com.ecat.integration.ModbusIntegration;

/**
 * Represents the information required to connect to a Modbus TCP device.
 * This includes the IP address, port number, slave ID, protocol type(Modbus TCP or RTU over TCP),
 * and transaction timeout.
 *
 * @author coffee
 */
public class ModbusTcpInfo extends ModbusInfo {
    private final String ipAddress;  // IP地址
    private final Integer port;      // 端口号
    private final Integer timeout;   // Modbus 事务超时时间（毫秒）

    /**
     * 构造函数，默认使用 Modbus over TCP 帧格式，timeout 使用默认值
     * @param ipAddress IP地址
     * @param port 端口号
     * @param slaveId 从站ID
     */
    public ModbusTcpInfo(String ipAddress, Integer port, Integer slaveId) {
        this(ipAddress, port, slaveId, ModbusProtocol.TCP, Const.DEFAULT_TCP_TIMEOUT_MS);
    }

    /**
     * 构造函数，允许指定帧格式，timeout 使用默认值
     * @param ipAddress IP地址
     * @param port 端口号
     * @param slaveId 从站ID
     * @param frameFormat 帧格式, ModbusProtocol.TCP 或 ModbusProtocol.RTU_OVER_TCP
     *
     * @see ModbusProtocol
     */
    public ModbusTcpInfo(String ipAddress, Integer port, Integer slaveId, ModbusProtocol frameFormat) {
        this(ipAddress, port, slaveId, frameFormat, Const.DEFAULT_TCP_TIMEOUT_MS);
    }

    /**
     * 构造函数，允许指定帧格式和事务超时时间
     * @param ipAddress IP地址
     * @param port 端口号
     * @param slaveId 从站ID
     * @param frameFormat 帧格式, ModbusProtocol.TCP 或 ModbusProtocol.RTU_OVER_TCP
     * @param timeout Modbus 事务超时时间（毫秒），对应 ModbusTcpCommConfigSchema 中的 timeout 字段
     *
     * @see ModbusProtocol
     */
    public ModbusTcpInfo(String ipAddress, Integer port, Integer slaveId, ModbusProtocol frameFormat, Integer timeout) {
        super(slaveId, frameFormat);
        this.ipAddress = ipAddress;
        this.port = port;
        this.timeout = timeout;
    }

    @Override
    public String toString() {
        return "ModbusTcpInfo{" +
                "ipAddress='" + ipAddress + '\'' +
                ", port=" + port +
                ", slaveId=" + getSlaveId() +
                ", protocol=" + getProtocol() +
                ", timeout=" + timeout +
                '}';
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public Integer getPort() {
        return port;
    }

    /**
     * 获取 Modbus 事务超时时间（毫秒）
     * @return 超时时间，用于 modbusMaster.setTimeout()
     */
    public Integer getTimeout() {
        return timeout;
    }
}
