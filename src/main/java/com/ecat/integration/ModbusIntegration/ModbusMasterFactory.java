package com.ecat.integration.ModbusIntegration;

import com.ecat.integration.SerialIntegration.SerialSource;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.ip.IpParameters;
import com.serotonin.modbus4j.serial.SerialPortWrapper;

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
     * 创建ModbusMaster实例
     * @param info Modbus设备信息（TCP或串行）
     * @return ModbusMaster实例
     * @throws ModbusInitException 初始化异常
     */
    public static ModbusMaster createModbusMaster(ModbusInfo info) throws ModbusInitException {
        ModbusFactory factory = new ModbusFactory();

        if (info instanceof ModbusTcpInfo) {
            return createTcpMaster((ModbusTcpInfo) info, factory);
        } else if (info instanceof ModbusSerialInfo) {
            return createSerialMaster((ModbusSerialInfo) info, factory);
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
        SerialPortWrapper serialPortWrapper = new ModbusSerialPortWrapper(serialInfo, serialSource);
        ModbusMaster modbusMaster = factory.createRtuMaster(serialPortWrapper);
        modbusMaster.setTimeout(serialInfo.getTimeout());
        // 注册映射：销毁时需要释放 SerialSource
        serialMasterRegistry.put(modbusMaster, serialSource);
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
        if (master != null && master.isInitialized()) {
            master.destroy();
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

    private static ModbusMaster createSerialMaster(ModbusSerialInfo serialInfo, ModbusFactory factory) {
        SerialPortWrapper serialPortWrapper = new ModbusSerialPortWrapper(serialInfo);
        ModbusMaster modbusMaster = factory.createRtuMaster(serialPortWrapper); // RTU模式
        modbusMaster.setTimeout(serialInfo.getTimeout());
        // modbusMaster.setDiscardDataDelay((int)(serialInfo.getTimeout()*1.2));
        return modbusMaster;
    }
}
