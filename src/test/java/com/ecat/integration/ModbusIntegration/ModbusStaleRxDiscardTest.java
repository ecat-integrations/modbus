package com.ecat.integration.ModbusIntegration;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

import org.junit.Test;

import com.ecat.integration.SerialIntegration.SerialSource;
import com.serotonin.modbus4j.ModbusMaster;

/**
 * F-43 modbus 对齐（用户指引：对齐 serial「每次发送命令前 clearReceiveBuffer」纪律）：
 * TCP 复用连接（keepAlive）的 MBAP 流错位防护——上一事务超时被强拆后，迟到应答字节落在
 * 共享 socket 流里会与下一事务的响应错位组帧。
 *
 * <p>实现选型：不做跨线程 socket 直读排空（modbus4j 读线程与调用线程并发读同一 InputStream
 * 会在帧边界撕裂），改用 modbus4j MessageControl 内建的 {@code discardDataDelay}——数据在
 * 静默超过阈值后到达时清空解析缓冲再入栈（在读线程内执行，串行无竞态；TCP 与 RTU 的
 * sendImpl 同走 MessageControl.waitingRoom 路径，两路同获保护）。RTU 路径另经 serial 的
 * recoverWedgedPort 重开清洗（F-43 清洗②）兜内核残留。
 *
 * <p>本测试钉住接线：master 创建时 discardDataDelay = 设备请求超时（迟到阈值与单请求超时
 * 同刻度——静默超过一次请求超时后到达的字节必是死事务残留）。
 */
public class ModbusStaleRxDiscardTest {

    @Test
    public void tcpMaster_discardDataDelayWiredToRequestTimeout() throws Exception {
        ModbusTcpInfo tcpInfo = new ModbusTcpInfo("127.0.0.1", 15020, 1, ModbusProtocol.TCP, 800);
        ModbusMaster master = ModbusMasterFactory.createModbusMaster(tcpInfo);
        assertEquals("TCP master 须接线 discardDataDelay=请求超时（陈旧 RX 静默丢弃阈值）",
                (int) tcpInfo.getTimeout(), master.getDiscardDataDelay());
    }

    @Test
    public void rtuMaster_discardDataDelayWiredToRequestTimeout() throws Exception {
        ModbusSerialInfo serialInfo = new ModbusSerialInfo("/dev/ttyTEST2", 9600, 8, 1, 0, 500, 1);
        ModbusMaster master = ModbusMasterFactory.createSerialMaster(serialInfo, mock(SerialSource.class));
        assertEquals("RTU master 须接线 discardDataDelay=请求超时（陈旧 RX 静默丢弃阈值）",
                serialInfo.getTimeout(), master.getDiscardDataDelay());
    }
}
