package com.ecat.integration.ModbusIntegration;

import com.fazecast.jSerialComm.SerialPort;
import com.serotonin.modbus4j.serial.SerialPortWrapper;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * ModbusSerialPortWrapper类用于封装Modbus串行设备的串口操作。
 * 它实现了SerialPortWrapper接口，提供打开、关闭串口和获取输入输出流的方法。
 * 
 * @author coffee
 */
public class ModbusSerialPortWrapper implements SerialPortWrapper {
    private final SerialPort serialPort;
    private final int timeout;  // 超时时间（毫秒）

    public ModbusSerialPortWrapper(ModbusSerialInfo info) {
        this.serialPort = SerialPort.getCommPort(info.getPortName());
        this.timeout = info.getTimeout();
        configurePort(info);
    }

    private void configurePort(ModbusSerialInfo info) {
        // 配置串口基础参数
        serialPort.setBaudRate(info.getBaudrate());
        serialPort.setNumDataBits(info.getDataBits());
        serialPort.setNumStopBits(info.getStopBits());
        serialPort.setParity(info.getParity());
        serialPort.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);

        // 配置超时（同时启用读取和写入超时）
        int timeoutMode = SerialPort.TIMEOUT_READ_SEMI_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING;
        serialPort.setComPortTimeouts(timeoutMode, timeout, timeout);
    }

    @Override
    public void open() throws Exception {
        if (!serialPort.openPort()) {
            throw new RuntimeException("无法打开串口: " + serialPort.getSystemPortName());
        }
    }

    @Override
    public void close() throws Exception {
        serialPort.closePort();
    }

    @Override
    public InputStream getInputStream() {
        return serialPort.getInputStream();
    }

    @Override
    public OutputStream getOutputStream() {
        return serialPort.getOutputStream();
    }

    @Override
    public int getBaudRate() { return serialPort.getBaudRate(); }
    @Override
    public int getStopBits() { return serialPort.getNumStopBits(); }
    @Override
    public int getParity() { return serialPort.getParity(); }
    @Override
    public int getDataBits() { return serialPort.getNumDataBits(); }
}
