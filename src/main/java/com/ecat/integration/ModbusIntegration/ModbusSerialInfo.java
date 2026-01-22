package com.ecat.integration.ModbusIntegration;

/**
 * ModbusSerialInfo类用于表示Modbus串行设备(RTU)的信息，包括串口号、波特率、数据位、停止位、校验位和超时设置。
 * 
 * @author coffee
 */
public class ModbusSerialInfo extends ModbusInfo {
    
    // 校验位常量（与jSerialComm兼容）
    public static final int NO_PARITY = 0;
    public static final int ODD_PARITY = 1;
    public static final int EVEN_PARITY = 2;
    public static final int MARK_PARITY = 3;
    public static final int SPACE_PARITY = 4;

    // 停止位常量（与jSerialComm兼容）
    public static final int ONE_STOP_BIT = 1;
    public static final int ONE_POINT_FIVE_STOP_BITS = 2;
    public static final int TWO_STOP_BITS = 3;

    private final String portName;
    private final int baudrate;
    private final int dataBits;
    private final int stopBits;
    private final int parity;
    private final int timeout;  // 新增：超时设置（毫秒）

    public ModbusSerialInfo(String portName, int baudrate, int dataBits, 
                           int stopBits, int parity, int timeout, Integer slaveId) {
        super(slaveId, ModbusProtocol.SERIAL);
        validateParameters(portName, baudrate, dataBits, stopBits, parity, timeout);
        
        this.portName = portName;
        this.baudrate = baudrate;
        this.dataBits = dataBits;
        this.stopBits = stopBits;
        this.parity = parity;
        this.timeout = timeout;
    }

    @Override
    public String toString() {
        return "ModbusSerialInfo{" +
                "portName='" + portName + '\'' +
                ", baudrate=" + baudrate +
                ", dataBits=" + dataBits +
                ", stopBits=" + stopBits +
                ", parity=" + parity +
                ", timeout=" + timeout +
                ", slaveId=" + getSlaveId() +
                ", protocol=" + getProtocol() +
                '}';
    }

    private void validateParameters(String portName, int baudrate, int dataBits, 
                                   int stopBits, int parity, int timeout) {
        // 串口号校验
        if (portName == null || portName.trim().isEmpty()) {
            throw new IllegalArgumentException("串口号不能为空");
        }

        // 波特率校验
        if (baudrate <= 0) {
            throw new IllegalArgumentException("无效波特率（常见值：4800,9600,19200,115200）");
        }

        // 数据位校验（5-8）
        if (dataBits < 5 || dataBits > 8) {
            throw new IllegalArgumentException("数据位必须为5-8");
        }

        // 停止位校验（1,2,3）
        if (stopBits < ONE_STOP_BIT || stopBits > TWO_STOP_BITS) {
            throw new IllegalArgumentException("停止位必须为1（1位）、2（1.5位）或3（2位）");
        }

        // 校验位校验（0-4）
        if (parity < NO_PARITY || parity > SPACE_PARITY) {
            throw new IllegalArgumentException("校验位必须为0（无）、1（奇）、2（偶）、3（标记）、4（空格）");
        }

        // 超时校验
        if (timeout < 0) {
            throw new IllegalArgumentException("超时时间不能为负数");
        }
    }

    // Getter方法
    public String getPortName() { return portName; }
    public int getBaudrate() { return baudrate; }
    public int getDataBits() { return dataBits; }
    public int getStopBits() { return stopBits; }
    public int getParity() { return parity; }
    public int getTimeout() { return timeout; }
}
