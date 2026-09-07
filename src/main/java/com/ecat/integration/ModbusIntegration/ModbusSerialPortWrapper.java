package com.ecat.integration.ModbusIntegration;

import com.ecat.integration.SerialIntegration.SerialSource;
import com.fazecast.jSerialComm.SerialPortIOException;
import com.serotonin.modbus4j.serial.SerialPortWrapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * ModbusSerialPortWrapper类用于封装Modbus串行设备的串口操作。
 * 它实现了SerialPortWrapper接口，提供打开、关闭串口和获取输入输出流的方法。
 *
 * <p>通过 serial integration 管理串口（{@link #ModbusSerialPortWrapper(ModbusSerialInfo, SerialSource)}），
 * 直接使用底层 SerialPort 的 InputStream/OutputStream，并在 open() 时暂停
 * SerialSourceEventAdapter 防止数据竞争。</p>
 *
 * @author coffee
 */
public class ModbusSerialPortWrapper implements SerialPortWrapper {

    private final SerialSource serialSource;    // 来自 serial integration

    // --- 共享字段 ---
    // private final int timeout;  // 超时时间（毫秒），已经交给modbus处理
    private final int baudRate;
    private final int dataBits;
    private final int stopBits;
    private final int parity;

    // 标记 event adapter 是否已暂停（防止重复暂停/恢复）
    private volatile boolean adapterPaused = false;

    /**
     * 构造函数：通过 serial integration 管理串口
     *
     * <p>直接使用底层 SerialPort 的 InputStream/OutputStream，
     * 在 open() 时暂停 SerialSourceEventAdapter 防止数据竞争，
     * 在 destroy() 时恢复 event adapter。</p>
     *
     * @param info         串口配置信息
     * @param serialSource  来自 serial integration 的串口资源
     */
    public ModbusSerialPortWrapper(ModbusSerialInfo info, SerialSource serialSource) {
        this.serialSource = serialSource;
        // this.timeout = info.getTimeout();
        this.baudRate = info.getBaudrate();
        this.dataBits = info.getDataBits();
        this.stopBits = info.getStopBits();
        this.parity = info.getParity();
    }

    @Override
    public void open() throws Exception {
        // Modbus4J 的 SerialMaster.openConnection() 会在每次事务前
        // 调用 close()→open()。串口已由 SerialSource 打开，这里只需暂停
        // event adapter 防止它读取数据（避免与 InputStream 竞争）。
        if (!adapterPaused) {
            serialSource.pauseEventAdapter();
            adapterPaused = true;
        }
    }

    @Override
    public void close() throws Exception {
        // Modbus4J 的 SerialMaster.closeConnection() 会在每次事务后调用此方法。
        // 不关闭串口（由 SerialSource 管理生命周期），也不恢复 event adapter
        // （因为下一次 open() 会很快到来，频繁暂停/恢复没有必要）。
        // 真正的资源释放由 destroyMaster() → SerialSource.closePort() 处理。
    }

    /**
     * 真正的资源释放：恢复 event adapter。
     * 由 ModbusMasterFactory.destroyMaster() 或 ModbusSlaveServer.stop() 调用。
     */
    public void destroy() {
        if (serialSource != null && adapterPaused) {
            serialSource.resumeEventAdapter();
            adapterPaused = false;
        }
    }

    @Override
    public InputStream getInputStream() {
        // event adapter 已在 open() 中暂停，不会竞争数据。
        // jSerialComm 断口异常文案不在 modbus4j listener 的退出白名单内，须翻译后才交给
        // modbus4j，否则对端永久离线时读线程 50ms 循环永续（磁盘刷爆事故根因）。
        return new JSerialCommExitTranslationInputStream(serialSource.getSerialPort().getInputStream());
    }

    @Override
    public OutputStream getOutputStream() {
        return serialSource.getSerialPort().getOutputStream();
    }

    @Override
    public int getBaudRate() { return baudRate; }
    @Override
    public int getStopBits() { return stopBits; }
    @Override
    public int getParity() { return parity; }
    @Override
    public int getDataBits() { return dataBits; }

    /**
     * 把 jSerialComm 断口异常翻译成 modbus4j InputStreamListener 退出白名单文案的输入流。
     *
     * <p>modbus4j（固定 v3.1.9，不动第三方）的 InputStreamListener.run() 捕获 IOException 后，
     * 仅当 getMessage() equals "Stream closed."（原配 serotonin 串口库的文案）或 contains
     * "nativeavailable" 才置 running=false 退出循环，其余一律按临时错误处理、每 50ms 重试。
     * 我们用 jSerialComm，断口（对端拔除/关闭）抛 SerialPortIOException("This port appears
     * to have been shutdown or disconnected.")，不匹配白名单 → 对端永久离线时循环永续，
     * 形成每口 20 次/秒的异常风暴（40 口并发刷爆磁盘，bug-record-20260907-093000）。
     * 在流边界把该异常翻译成白名单文案，listener 首颗异常即退出读线程。</p>
     *
     * <p>方案取舍：不选 DataConsumer/handler 侧处理——handler 只能旁路观察异常，改不了
     * listener 内部控制流，50ms 空转与 printStackTrace 风暴仍在；也不改 modbus4j 源码
     * （第三方固定版本）。本类是唯一翻译点：全部串口设备（master 与 slave）都经
     * wrapper.getInputStream() 取流，单点覆盖所有串口。</p>
     */
    static class JSerialCommExitTranslationInputStream extends InputStream {

        private final InputStream delegate;

        JSerialCommExitTranslationInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        /**
         * 翻译断口异常。文案必须逐字 equals "Stream closed."（listener 用 StringUtils.equals
         * 精确匹配白名单）；cause 保留原始异常，排障时不丢真实断口信息。
         */
        private static IOException translate(SerialPortIOException e) {
            IOException translated = new IOException("Stream closed.");
            translated.initCause(e);
            return translated;
        }

        @Override
        public int available() throws IOException {
            try {
                return delegate.available();
            } catch (SerialPortIOException e) {
                throw translate(e);
            }
        }

        @Override
        public int read() throws IOException {
            try {
                return delegate.read();
            } catch (SerialPortIOException e) {
                throw translate(e);
            }
        }

        @Override
        public int read(byte[] b) throws IOException {
            try {
                return delegate.read(b);
            } catch (SerialPortIOException e) {
                throw translate(e);
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                return delegate.read(b, off, len);
            } catch (SerialPortIOException e) {
                throw translate(e);
            }
        }

        @Override
        public void close() throws IOException {
            // close 纯委托：listener 不在 close 路径上做白名单判断，无需翻译。
            delegate.close();
        }
    }
}
