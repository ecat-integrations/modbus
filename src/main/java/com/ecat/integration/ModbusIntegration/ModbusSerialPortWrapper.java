package com.ecat.integration.ModbusIntegration;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortIOException;
import com.serotonin.modbus4j.serial.SerialPortWrapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * ModbusSerialPortWrapper类用于封装Modbus串行设备的串口操作。
 * 它实现了SerialPortWrapper接口，提供打开、关闭串口和获取输入输出流的方法。
 * 它支持端口故障时的自动重连机制，在读取数据时如果发生SerialPortIOException异常，会等待1秒钟后尝试重新打开串口。
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
        // Wrap InputStream with auto-reconnect on SerialPortIOException.
        // When port is disconnected, wait 1s and try to reopen.
        // If reconnect succeeds, continue working; otherwise throw original exception.
        return new ReconnectingInputStream();
    }

    /**
     * InputStream wrapper that attempts automatic reconnection when the serial port is disconnected.
     * On SerialPortIOException, it waits 1 second and tries to reopen the port.
     */
    private class ReconnectingInputStream extends InputStream {
        private InputStream delegate = serialPort.getInputStream();

        /**
         * Attempts to reconnect the serial port after a failure.
         * @param originalException the exception that triggered the reconnect attempt
         * @throws SerialPortIOException if reconnection fails
         */
        private void tryReconnect(SerialPortIOException originalException) throws SerialPortIOException {
            try {
                Thread.sleep(1000);  // Wait 1 second before reconnect attempt
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw originalException;
            }

            if (serialPort.openPort()) {
                // Reconnect successful, get fresh InputStream
                delegate = serialPort.getInputStream();
            } else {
                // Reconnect failed, throw original exception
                throw originalException;
            }
        }

        @Override
        public int read() throws IOException {
            while (true) {
                try {
                    return delegate.read();
                } catch (SerialPortIOException e) {
                    tryReconnect(e);
                    // Loop again to retry read after successful reconnect
                }
            }
        }

        @Override
        public int read(byte[] b) throws IOException {
            while (true) {
                try {
                    return delegate.read(b);
                } catch (SerialPortIOException e) {
                    tryReconnect(e);
                    // Loop again to retry read after successful reconnect
                }
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            while (true) {
                try {
                    return delegate.read(b, off, len);
                } catch (SerialPortIOException e) {
                    tryReconnect(e);
                    // Loop again to retry read after successful reconnect
                }
            }
        }

        @Override
        public int available() throws IOException {
            while (true) {
                try {
                    return delegate.available();
                } catch (SerialPortIOException e) {
                    tryReconnect(e);
                    // Loop again to retry available after successful reconnect
                }
            }
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
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
