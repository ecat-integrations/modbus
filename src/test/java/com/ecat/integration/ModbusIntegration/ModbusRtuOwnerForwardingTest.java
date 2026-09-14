package com.ecat.integration.ModbusIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import com.ecat.core.CommTrace.OwnerLevel;
import com.ecat.core.CommTrace.ResourceKind;
import com.ecat.core.CommTrace.ResourceOwner;
import com.ecat.core.CommTrace.ResourceRef;
import com.ecat.core.CommTrace.Usage;
import com.ecat.core.Integration.IntegrationManager;
import com.ecat.core.Integration.IntegrationRegistry;
import com.ecat.integration.ModbusIntegration.Sdk.ModbusSdkTimers;
import com.ecat.integration.SerialIntegration.SerialIntegration;
import com.serotonin.modbus4j.ModbusMaster;

/**
 * RTU 带主转发账本（io-resource-owner 设计 §5.3「RTU 子注册带主」+ §4 借用带主 + §8 RTU 行）：
 * modbus register 把调用者 owner 经 asAdapter() 原样转发 serial（不再自造 "modbus-端口"
 * 中间商）——同口 N 设备 = serial 账本 N 条 (DEVICE,…,ADAPTER) 条目；注销摘除、摘空拆视图；
 * 跨集成共线（aogan/saimosen 同总线形态）多家各一条。
 *
 * <p>真实 SerialIntegration 承载串口账本（"/dev/null" 真实路径；测试栈检测使端口不启轮询），
 * ModbusMasterFactory mock 静态拦截 master 创建（账本语义不依赖真实 RTU 传输）。
 */
public class ModbusRtuOwnerForwardingTest {

    private static final String PORT = "/dev/null";
    private static final String COORD_AOGAN = "com.ecat:integration-aogan";
    private static final String COORD_SAIMOSEN = "com.ecat:integration-saimosen";

    private ModbusIntegration modbusIntegration;
    private SerialIntegration serialIntegration;
    private AutoCloseable mockitoCloseable;
    private MockedStatic<ModbusMasterFactory> factoryMock;

    @Before
    public void setUp() throws Exception {
        mockitoCloseable = MockitoAnnotations.openMocks(this);
        // master 创建拦截：createSerialMaster 返回 mock（真实 RtuMaster 需真实串口流，账本不依赖）
        factoryMock = Mockito.mockStatic(ModbusMasterFactory.class);
        ModbusMaster mockMaster = mock(ModbusMaster.class);
        doNothing().when(mockMaster).init();
        when(mockMaster.isInitialized()).thenReturn(true);
        factoryMock.when(() -> ModbusMasterFactory.createSerialMaster(any(ModbusSerialInfo.class), any()))
                .thenReturn(mockMaster);

        serialIntegration = new SerialIntegration();

        modbusIntegration = new ModbusIntegration();
        IntegrationRegistry registry = mock(IntegrationRegistry.class);
        when(registry.getIntegration("integration-serial")).thenReturn(serialIntegration);
        IntegrationManager integrationManager = mock(IntegrationManager.class);
        Map<String, Object> config = new HashMap<>();
        config.put("max_waiters", 5);
        config.put("wait_timeout", 2000);
        when(integrationManager.loadConfig(anyString())).thenReturn(config);
        setSuperclassField(modbusIntegration, "integrationManager", integrationManager);
        setSuperclassField(modbusIntegration, "integrationRegistry", registry);
        modbusIntegration.onInit();
    }

    private static void setSuperclassField(Object target, String name, Object value) throws Exception {
        Field f = ModbusIntegration.class.getSuperclass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @After
    public void tearDown() throws Exception {
        if (modbusIntegration != null) {
            modbusIntegration.onRelease();
        }
        ModbusIoPool.resetForTest();
        ModbusSdkTimers.resetForTest();
        if (factoryMock != null) {
            factoryMock.close();
        }
        mockitoCloseable.close();
    }

    private static ModbusSerialInfo info() {
        return new ModbusSerialInfo(PORT, 9600, 8, 1, 0, 500, 1);
    }

    private List<ResourceOwner> serialDeviceOwners() {
        return serialIntegration.getDeviceOwners(new ResourceRef(ResourceKind.SERIAL_PORT, PORT));
    }

    // ==================== 每设备一条 ADAPTER 条目 ====================

    /** 同口两设备：serial 账本各一条 (DEVICE,…,ADAPTER)，身份=最终使用者（穿透转手）。 */
    @Test
    public void perDeviceAdapterEntriesInSerialLedger() {
        modbusIntegration.register(info(), ResourceOwner.device(COORD_AOGAN, "entry-1", "dev-1"));
        modbusIntegration.register(info(), ResourceOwner.device(COORD_AOGAN, "entry-1", "dev-2"));

        List<ResourceOwner> owners = serialDeviceOwners();
        assertEquals("同口 N 设备 = N 条 ADAPTER 条目（无中间商）", 2, owners.size());
        for (ResourceOwner owner : owners) {
            assertEquals("借用带主：登记最终使用者 DEVICE 层", OwnerLevel.DEVICE, owner.getLevel());
            assertEquals("ADAPTER 标注路径经 modbus 转手", Usage.ADAPTER, owner.getUsage());
            assertEquals(COORD_AOGAN, owner.getCoordinate());
        }
    }

    /** 首注册视图兼作 master 传输通道：两设备共享同一 modbus 源（既有共享语义不回退）。 */
    @Test
    public void sharedModbusSourceUnchangedAcrossDevices() throws Exception {
        ModbusSource s1 = modbusIntegration.register(info(), ResourceOwner.device(COORD_AOGAN, "entry-1", "dev-1"));
        ModbusSource s2 = modbusIntegration.register(info(), ResourceOwner.device(COORD_AOGAN, "entry-1", "dev-2"));

        Field f = DeviceSpecificModbusSource.class.getDeclaredField("delegate");
        f.setAccessible(true);
        assertSameByRef(s1, s2, f);
    }

    private static void assertSameByRef(ModbusSource a, ModbusSource b, Field delegateField) throws Exception {
        Object d1 = delegateField.get(a);
        Object d2 = delegateField.get(b);
        assertTrue("两设备委托同一共享 modbus 源", d1 == d2);
    }

    // ==================== 注销摘除、摘空拆视图 ====================

    /** 逐设备注销：serial 账本逐条摘除；末源注销拆串口视图（引用计数天然对上）。 */
    @Test
    public void unregisterRemovesAdapterEntryLastTeardownDismantles() {
        ModbusSource s1 = modbusIntegration.register(info(), ResourceOwner.device(COORD_AOGAN, "entry-1", "dev-1"));
        ModbusSource s2 = modbusIntegration.register(info(), ResourceOwner.device(COORD_AOGAN, "entry-2", "dev-2"));
        assertEquals(2, serialDeviceOwners().size());

        s1.closeModbus();
        assertEquals("摘一条少一条", 1, serialDeviceOwners().size());

        s2.closeModbus();
        assertEquals("摘空拆视图：serial 账本清空", 0, serialDeviceOwners().size());
    }

    /** 先注销者视图早摘不伤后继：master 传输由共享串口对象承载，末源才拆。 */
    @Test
    public void earlyUnregisterKeepsRemainingDeviceRegistered() {
        ModbusSource s1 = modbusIntegration.register(info(), ResourceOwner.device(COORD_AOGAN, "entry-1", "dev-1"));
        ModbusSource s2 = modbusIntegration.register(info(), ResourceOwner.device(COORD_AOGAN, "entry-2", "dev-2"));

        s1.closeModbus();
        List<ResourceOwner> owners = serialDeviceOwners();
        assertEquals(1, owners.size());
        assertEquals("剩余条目是未注销设备", "dev-2", owners.get(0).getDeviceId());
        assertNotNull(s2);
    }

    // ==================== 跨集成共线 ====================

    /** 跨集成共线（aogan/saimosen 同总线形态）：多家各一条，折叠到集成一眼答。 */
    @Test
    public void crossIntegrationSharedBusEntriesPerIntegration() {
        modbusIntegration.register(info(), ResourceOwner.device(COORD_AOGAN, "entry-1", "dev-1"));
        modbusIntegration.register(info(), ResourceOwner.device(COORD_SAIMOSEN, "entry-2", "dev-2"));

        List<ResourceOwner> owners = serialDeviceOwners();
        assertEquals("共线多家各一条", 2, owners.size());
        List<ResourceOwner> integrations = serialIntegration.getIntegrationOwners(
                new ResourceRef(ResourceKind.SERIAL_PORT, PORT));
        assertEquals("折叠到集成：两家各占一条", 2, integrations.size());
    }
}
