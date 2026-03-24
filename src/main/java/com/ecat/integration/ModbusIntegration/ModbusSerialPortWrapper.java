package com.ecat.integration.ModbusIntegration;

import com.ecat.integration.SerialIntegration.SerialSource;
import com.serotonin.modbus4j.serial.SerialPortWrapper;

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
        return serialSource.getSerialPort().getInputStream();
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
}
