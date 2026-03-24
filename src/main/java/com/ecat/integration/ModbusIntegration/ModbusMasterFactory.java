package com.ecat.integration.ModbusIntegration;

import com.ecat.integration.SerialIntegration.SerialSource;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.ip.IpParameters;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ModbusMaster工厂类，根据设备信息类型创建TCP或串行Master。
 *
 * <p>工厂还负责管理传输资源的生命周期：RTU 模式下，
 * SerialSource 与 ModbusMaster 的关联由工厂内部跟踪，
 * 通过 {@link #destroyMaster(ModbusMaster)} 统一释放。
 *
 * @author coffee
 */
public class ModbusMasterFactory {

    /**
     * RTU 模式下 master → SerialSource 的映射，用于销毁时释放串口资源
     */
    private static final Map<ModbusMaster, SerialSource> serialMasterRegistry = new ConcurrentHashMap<>();

    /**
     * RTU 模式下 master → ModbusSerialPortWrapper 的映射，用于销毁时恢复 event adapter
     */
    private static final Map<ModbusMaster, ModbusSerialPortWrapper> wrapperRegistry = new ConcurrentHashMap<>();

    /**
     * 创建ModbusMaster实例（仅 TCP）
     * @param info Modbus设备信息（仅支持 TCP）
     * @return ModbusMaster实例
     * @throws ModbusInitException 初始化异常
     */
    public static ModbusMaster createModbusMaster(ModbusInfo info) throws ModbusInitException {
        if (info instanceof ModbusTcpInfo) {
            ModbusFactory factory = new ModbusFactory();
            return createTcpMaster((ModbusTcpInfo) info, factory);
        } else if (info instanceof ModbusSerialInfo) {
            throw new IllegalArgumentException("RTU 设备必须使用 createSerialMaster(info, serialSource)");
        } else {
            throw new IllegalArgumentException("未知的Modbus协议类型");
        }
    }

    /**
     * 创建串行 ModbusMaster（通过 serial integration 管理串口）
     *
     * <p>创建后自动注册 master → SerialSource 映射，
     * 销毁时通过 {@link #destroyMaster(ModbusMaster)} 统一释放。
     *
     * @param serialInfo 串口配置
     * @param serialSource 来自 serial integration 的串口资源
     * @return ModbusMaster实例
     * @throws ModbusInitException 初始化异常
     */
    public static ModbusMaster createSerialMaster(ModbusSerialInfo serialInfo, SerialSource serialSource) throws ModbusInitException {
        ModbusFactory factory = new ModbusFactory();
        ModbusSerialPortWrapper serialPortWrapper = new ModbusSerialPortWrapper(serialInfo, serialSource);
        ModbusMaster modbusMaster = factory.createRtuMaster(serialPortWrapper);
        modbusMaster.setTimeout(serialInfo.getTimeout());
        // 注册映射：销毁时需要恢复 event adapter + 释放 SerialSource
        serialMasterRegistry.put(modbusMaster, serialSource);
        wrapperRegistry.put(modbusMaster, serialPortWrapper);
        return modbusMaster;
    }

    /**
     * 统一销毁 ModbusMaster 及其关联的传输资源。
     *
     * <p>处理 TCP 和 RTU 的差异：
     * <ul>
     *   <li>TCP：仅销毁 ModbusMaster</li>
     *   <li>RTU（通过 serial integration）：销毁 ModbusMaster + 释放 SerialSource</li>
     * </ul>
     *
     * @param master 要销毁的 ModbusMaster
     */
    public static void destroyMaster(ModbusMaster master) {
        SerialSource serialSource = serialMasterRegistry.remove(master);
        ModbusSerialPortWrapper wrapper = wrapperRegistry.remove(master);
        if (master != null && master.isInitialized()) {
            master.destroy();
        }
        // 恢复 event adapter（wrapper.destroy() 内部会检查 adapterPaused 标志）
        if (wrapper != null) {
            wrapper.destroy();
        }
        if (serialSource != null) {
            serialSource.closePort();
        }
    }

    private static ModbusMaster createTcpMaster(ModbusTcpInfo tcpInfo, ModbusFactory factory) {
        IpParameters ipParams = new IpParameters();
        ipParams.setHost(tcpInfo.getIpAddress());
        ipParams.setPort(tcpInfo.getPort());
        if(tcpInfo.getProtocol() == ModbusProtocol.RTU_OVER_TCP) {
            ipParams.setEncapsulated(true); // RTU over TCP
        } else {
            ipParams.setEncapsulated(false); // 标准 Modbus TCP
        }
        return factory.createTcpMaster(ipParams, false); // false表示非保持连接
    }
}
