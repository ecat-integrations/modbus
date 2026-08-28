package com.ecat.integration.ModbusIntegration;

import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.net.Socket;

import org.junit.Test;

import com.serotonin.modbus4j.ip.IpParameters;
import com.serotonin.modbus4j.ip.tcp.TcpMaster;

/**
 * Q-1/A2 挂死强拆（wedge recovery）的委托与安全契约测试：
 * <ul>
 *   <li>DeviceSpecificModbusSource（与 delegate 共用源锁）的事务硬超时路径必须把强拆落到
 *       真实持有传输的 delegate 上；</li>
 *   <li>{@link ModbusMasterFactory#forceRecover} 对未建连的 TcpMaster（socket==null）必须
 *       安全无操作（不抛异常——强拆失败不致命，下一超时路径重试）。</li>
 * </ul>
 *
 * @author coffee
 */
public class ModbusWedgeRecoveryContractTest {

    /** 契约：DeviceSpecific 的 forceRecoverTransport 委托给共享 delegate（master 归 delegate 所有）。 */
    @Test
    public void deviceSpecificDelegatesForceRecoverTransport() {
        ModbusSource delegate = mock(ModbusSource.class);
        ModbusTcpInfo info = new ModbusTcpInfo("127.0.0.1", 1502, 1);
        DeviceSpecificModbusSource deviceSource = new DeviceSpecificModbusSource(delegate, info, "device-1");

        deviceSource.forceRecoverTransport("test-reason");

        verify(delegate, times(1)).forceRecoverTransport("test-reason");
    }

    /** 契约：forceRecover 对未建连 TcpMaster（socket 尚为 null）安全无操作、不抛异常。 */
    @Test
    public void forceRecoverSafeOnUnconnectedTcpMaster() {
        IpParameters params = new IpParameters();
        TcpMaster master = new TcpMaster(params, true);
        // 未 init：socket 字段为 null → 反射路径应静默跳过
        ModbusMasterFactory.forceRecover(master, "test-reason");
        assertNull("未建连 master 不应产生 socket", readSocketReflectively(master));
    }

    /** 契约：源已销毁（destroyed）时 forceRecoverTransport 跳过（不再触碰已释放资源）。 */
    @Test
    public void forceRecoverSkippedWhenSourceDestroyed() {
        ModbusSource source = new ModbusSource(new ModbusTcpInfo("127.0.0.1", 1502, 1)) {
            @Override
            public void closeModbus() {
            }
        };
        source.registerIntegration("test");
        source.closeModbus("test"); // 引用计数归零 → destroyResources → destroyed 置位
        // 不抛异常即通过（master 为 null 的防御路径 + destroyed 防线均不触碰传输）
        source.forceRecoverTransport("test-reason");
    }

    private static Socket readSocketReflectively(TcpMaster master) {
        try {
            Field f = TcpMaster.class.getDeclaredField("socket");
            f.setAccessible(true);
            return (Socket) f.get(master);
        } catch (Exception e) {
            return null;
        }
    }
}
