package com.ecat.integration.ModbusIntegration;

import com.ecat.integration.SerialIntegration.Listener.SerialDataListener;
import com.ecat.integration.SerialIntegration.SerialSource;
import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortIOException;
import com.serotonin.modbus4j.serial.SerialPortWrapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * ModbusSerialPortWrapper类用于封装Modbus串行设备的串口操作。
 * 它实现了SerialPortWrapper接口，提供打开、关闭串口和获取输入输出流的方法。
 * 它支持端口故障时的自动重连机制，在读取数据时如果发生SerialPortIOException异常，会等待1秒钟后尝试重新打开串口。
 *
 * <p>支持两种模式：</p>
 * <ul>
 *   <li>旧模式：直接打开和管理串口（{@link #ModbusSerialPortWrapper(ModbusSerialInfo)}）</li>
 *   <li>新模式：通过 serial integration 管理串口（{@link #ModbusSerialPortWrapper(ModbusSerialInfo, SerialSource)}），
 *       使用 SerialDataListener + BlockingQueue 将异步 I/O 桥接为同步 InputStream/OutputStream</li>
 * </ul>
 *
 * @author coffee
 */
public class ModbusSerialPortWrapper implements SerialPortWrapper {

    // --- 旧模式字段 ---
    private final SerialPort serialPort;       // 旧模式：直接管理的串口
    // --- 新模式字段 ---
    private final SerialSource serialSource;    // 新模式：来自 serial integration (null = 旧模式)

    // --- 共享字段 ---
    private final int timeout;  // 超时时间（毫秒）
    private final int baudRate;
    private final int dataBits;
    private final int stopBits;
    private final int parity;

    // 新模式：桥接异步 listener 到同步 InputStream
    private final BlockingQueue<byte[]> responseQueue = new LinkedBlockingQueue<>();
    private SerialDataListener dataListener;
    private volatile boolean closed = false;

    /**
     * 旧模式构造函数：直接打开和管理串口
     */
    public ModbusSerialPortWrapper(ModbusSerialInfo info) {
        this.serialPort = SerialPort.getCommPort(info.getPortName());
        this.serialSource = null;
        this.timeout = info.getTimeout();
        this.baudRate = info.getBaudrate();
        this.dataBits = info.getDataBits();
        this.stopBits = info.getStopBits();
        this.parity = info.getParity();
        configurePort(info);
    }

    /**
     * 新模式构造函数：通过 serial integration 管理串口
     *
     * <p>使用 {@link SerialDataListener} 接收数据，通过 {@link BlockingQueue}
     * 桥接到 Modbus4J 需要的同步 {@link InputStream}/{@link OutputStream}。</p>
     *
     * @param info         串口配置信息
     * @param serialSource  来自 serial integration 的串口资源
     */
    public ModbusSerialPortWrapper(ModbusSerialInfo info, SerialSource serialSource) {
        this.serialPort = null;
        this.serialSource = serialSource;
        this.timeout = info.getTimeout();
        this.baudRate = info.getBaudrate();
        this.dataBits = info.getDataBits();
        this.stopBits = info.getStopBits();
        this.parity = info.getParity();
    }

    private void configurePort(ModbusSerialInfo info) {
        serialPort.setBaudRate(info.getBaudrate());
        serialPort.setNumDataBits(info.getDataBits());
        serialPort.setNumStopBits(info.getStopBits());
        serialPort.setParity(info.getParity());
        serialPort.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);

        int timeoutMode = SerialPort.TIMEOUT_READ_SEMI_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING;
        serialPort.setComPortTimeouts(timeoutMode, timeout, timeout);
    }

    @Override
    public void open() throws Exception {
        if (serialSource != null) {
            // 新模式：注册 data listener，不打开串口（由 serial integration 管理）
            closed = false;
            responseQueue.clear();
            dataListener = new SerialDataListener() {
                @Override
                public void onDataReceived(byte[] data, int length) {
                    if (!closed) {
                        byte[] copy = Arrays.copyOf(data, length);
                        responseQueue.offer(copy);
                    }
                }
                @Override
                public void onError(Exception ex) {
                    // listener 不需要处理错误，超时由 read() 的 poll timeout 控制
                }
            };
            serialSource.addDataListener(dataListener);
        } else {
            // 旧模式：打开自己的串口
            if (!serialPort.openPort()) {
                throw new RuntimeException("无法打开串口: " + serialPort.getSystemPortName());
            }
        }
    }

    @Override
    public void close() throws Exception {
        if (serialSource != null) {
            closed = true;
            if (dataListener != null) {
                serialSource.removeDataListener(dataListener);
                dataListener = null;
            }
            responseQueue.clear();
            // 不关闭 SerialSource — 生命周期由 serial integration 管理
        } else {
            serialPort.closePort();
        }
    }

    @Override
    public InputStream getInputStream() {
        if (serialSource != null) {
            return new SerialSourceInputStream();
        }
        return new ReconnectingInputStream();
    }

    @Override
    public OutputStream getOutputStream() {
        if (serialSource != null) {
            return new SerialSourceOutputStream();
        }
        return serialPort.getOutputStream();
    }

    @Override
    public int getBaudRate() { return baudRate; }
    @Override
    public int getStopBits() { return stopBits; }
    @Override
    public int getParity() { return parity; }
    @Override
    public int getDataBits() { return dataBits; }

    // ==================== 旧模式：ReconnectingInputStream ====================

    /**
     * InputStream wrapper that attempts automatic reconnection when the serial port is disconnected.
     * On SerialPortIOException, it waits 1 second and tries to reopen the port.
     */
    private class ReconnectingInputStream extends InputStream {
        private InputStream delegate = serialPort.getInputStream();

        private void tryReconnect(SerialPortIOException originalException) throws SerialPortIOException {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw originalException;
            }

            if (serialPort.openPort()) {
                delegate = serialPort.getInputStream();
            } else {
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
                }
            }
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    // ==================== 新模式：SerialSource 桥接 ====================

    /**
     * 将 SerialSource 的异步 listener 数据桥接为同步 InputStream。
     *
     * <p>数据流：SerialPort -> SerialSourceEventAdapter -> SerialDataListener
     * -> responseQueue -> InputStream.read()。Modbus4J 通过阻塞 read()
     * 等待数据到达。</p>
     */
    private class SerialSourceInputStream extends InputStream {
        private byte[] currentBuffer;
        private int currentPos = 0;

        @Override
        public int read() throws IOException {
            if (closed) throw new IOException("Stream closed");

            if (currentBuffer != null && currentPos < currentBuffer.length) {
                return currentBuffer[currentPos++] & 0xFF;
            }

            try {
                byte[] data = responseQueue.poll(timeout, TimeUnit.MILLISECONDS);
                if (data == null) {
                    throw new SerialPortIOException("Read timeout (" + timeout + "ms)");
                }
                currentBuffer = data;
                currentPos = 1;
                return data[0] & 0xFF;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (closed) throw new IOException("Stream closed");
            if (b == null) throw new NullPointerException();
            if (off < 0 || len < 0 || off + len > b.length) throw new IndexOutOfBoundsException();

            int totalRead = 0;
            while (totalRead < len) {
                if (currentBuffer != null && currentPos < currentBuffer.length) {
                    int avail = currentBuffer.length - currentPos;
                    int toCopy = Math.min(avail, len - totalRead);
                    System.arraycopy(currentBuffer, currentPos, b, off + totalRead, toCopy);
                    currentPos += toCopy;
                    totalRead += toCopy;
                } else {
                    try {
                        byte[] data = responseQueue.poll(timeout, TimeUnit.MILLISECONDS);
                        if (data == null) {
                            return totalRead == 0 ? -1 : totalRead;
                        }
                        currentBuffer = data;
                        currentPos = 0;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted", e);
                    }
                }
            }
            return totalRead;
        }

        @Override
        public int available() {
            int avail = (currentBuffer != null) ? (currentBuffer.length - currentPos) : 0;
            return avail + responseQueue.size();
        }
    }

    /**
     * 将 OutputStream 的同步写入桥接到 SerialSource 的异步发送。
     *
     * <p>每次写入前清除 responseQueue 中的残留数据，然后通过
     * {@link SerialSource#asyncSendData(byte[])} 发送（内部会清除
     * continuousReceiveBuffer）。使用 {@code .join()} 阻塞等待发送完成，
     * 匹配 Modbus4J 期望的同步语义。</p>
     */
    private class SerialSourceOutputStream extends OutputStream {
        @Override
        public void write(byte[] b) throws IOException {
            if (closed) throw new IOException("Stream closed");
            responseQueue.clear();
            serialSource.asyncSendData(b).join();
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b});
        }
    }
}
