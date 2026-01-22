package com.ecat.integration.ModbusIntegration;

import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.ip.IpParameters;
import com.serotonin.modbus4j.serial.SerialPortWrapper;

/**
 * ModbusMaster工厂类，根据设备信息类型创建TCP或串行Master
 * 
 * @author coffee
 */
public class ModbusMasterFactory {

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
