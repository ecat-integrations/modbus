package com.ecat.integration.ModbusIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

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
import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Device.RemovalHost;
import com.ecat.core.Integration.IntegrationManager;
import com.ecat.core.Integration.IntegrationRegistry;
import com.ecat.integration.ModbusIntegration.Sdk.ModbusSdkTimers;
import com.serotonin.modbus4j.ModbusMaster;

/**
 * ModbusIntegration 资源账本 owner 化与精准查询契约（io-resource-owner 设计 §5.3 +
 * §9-10）：register(info, host) 三合一（owner 派生 + onRemove 绑定 + 终态守卫就地回收）、
 * owner 重载、LEGACY 混存（ModbusSource.registerIntegration(String) 过渡期包裹入账）、get*Info 一对一精准（未注册 null / 层级精确）、registerForDevice
 * 消费方一步得源、get*Owners 按层折叠（MODBUS_CONNECTION ref 守卫）。
 *
 * <p>TCP 形态承重（RTU 带主转发另见 ModbusRtuOwnerForwardingTest）；ModbusMasterFactory
 * mock 静态拦截 master 创建，账本语义不依赖真实传输。
 */
public class ModbusOwnerLedgerTest {

    private static final String COORD = "com.ecat:integration-mb-ledger-test";
    private static final String CONN = "192.168.1.100:502";

    private ModbusIntegration modbusIntegration;
    private AutoCloseable mockitoCloseable;
    private MockedStatic<ModbusMasterFactory> factoryMock;

    @Before
    public void setUp() throws Exception {
        mockitoCloseable = MockitoAnnotations.openMocks(this);
        factoryMock = Mockito.mockStatic(ModbusMasterFactory.class);
        ModbusMaster mockMaster = mock(ModbusMaster.class);
        doNothing().when(mockMaster).init();
        when(mockMaster.isInitialized()).thenReturn(true);
        factoryMock.when(() -> ModbusMasterFactory.createModbusMaster(any(ModbusInfo.class)))
                .thenReturn(mockMaster);

        modbusIntegration = new ModbusIntegration();
        IntegrationRegistry registry = mock(IntegrationRegistry.class);
        IntegrationManager integrationManager = mock(IntegrationManager.class);
        Map<String, Object> config = new HashMap<>();
        config.put("max_waiters", 5);
        config.put("wait_timeout", 2000);
        when(integrationManager.loadConfig(anyString())).thenReturn(config);
        Field m = ModbusIntegration.class.getSuperclass().getDeclaredField("integrationManager");
        m.setAccessible(true);
        m.set(modbusIntegration, integrationManager);
        Field r = ModbusIntegration.class.getSuperclass().getDeclaredField("integrationRegistry");
        r.setAccessible(true);
        r.set(modbusIntegration, registry);
        modbusIntegration.onInit();
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

    private static ModbusTcpInfo tcp(int slaveId) {
        return new ModbusTcpInfo("192.168.1.100", 502, slaveId);
    }

    private static ResourceOwner deviceOwner(String entryId, String deviceId) {
        return ResourceOwner.device(COORD, entryId, deviceId);
    }

    private List<ResourceOwner> deviceOwners() {
        return modbusIntegration.getDeviceOwners(new ResourceRef(ResourceKind.MODBUS_CONNECTION, CONN));
    }

    /** 收集型假宿主（onRemove 动作记账，测试手动触发）。 */
    private static final class RecordingHost implements RemovalHost {
        final List<Runnable> actions = new ArrayList<>();

        @Override
        public void onRemove(Runnable action) {
            actions.add(action);
        }
    }

    /** 入口最小设备桩（宿主派生路径）：entry 带 entryId+coordinate，of(host) 可派生 DEVICE 层。 */
    private static final class HostDevice extends DeviceBase {
        HostDevice(String entryId) {
            super(entryWith(entryId));
        }

        private static ConfigEntry entryWith(String entryId) {
            ConfigEntry entry = new ConfigEntry();
            entry.setEntryId(entryId);
            entry.setCoordinate(COORD);
            entry.setData(new HashMap<>());
            return entry;
        }

        @Override
        public void init() {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void release() {
        }
    }

    // ==================== register(info, host) 三合一 ====================

    /** 设备宿主注册：owner 从宿主派生（DEVICE 层、deviceId=getId()），宿主收尾自动摘账。 */
    @Test
    public void registerWithHostDerivesOwnerAndBindsLifecycle() {
        HostDevice device = new HostDevice("entry-9");

        ModbusSource source = modbusIntegration.register(tcp(1), device);
        assertNotNull(source);
        List<ResourceOwner> owners = deviceOwners();
        assertEquals(1, owners.size());
        ResourceOwner owner = owners.get(0);
        assertEquals(OwnerLevel.DEVICE, owner.getLevel());
        assertEquals("deviceId=getId() 稳定 UUID（四层身份模型）", device.getId(), owner.getDeviceId());
        assertEquals("entryId=getEntry().getEntryId()", "entry-9", owner.getEntryId());
        assertEquals(COORD, owner.getCoordinate());

        // 宿主收尾（LIFO sweep）→ 自动摘账
        device.cancelManagedTasks();
        assertEquals("宿主收尾自动摘账", 0, deviceOwners().size());
    }

    /** 终态守卫：宿主已 sweep 后注册属病态调用——拒绝并就地回收（不留半活账目）。 */
    @Test
    public void registerWithTerminalHostRejectedAndReclaimed() {
        HostDevice device = new HostDevice("entry-9");
        device.cancelManagedTasks(); // 宿主已终态

        try {
            modbusIntegration.register(tcp(1), device);
            fail("终态宿主注册应抛 RejectedExecutionException");
        } catch (RejectedExecutionException expected) {
            // EasyHttpClient 同型守卫
        }
        assertEquals("就地回收：不留半活账目", 0, deviceOwners().size());
        assertNull("身份未入账，精准查询如实 null",
                modbusIntegration.getDeviceInfo(COORD, "entry-9", device.getId()));
    }

    /** owner 重载：显式 owner 条目入账（与 host 派生同账本）。 */
    @Test
    public void registerWithOwnerOverloadEntersLedger() {
        modbusIntegration.register(tcp(1), deviceOwner("entry-1", "dev-1"));
        assertEquals(1, deviceOwners().size());
        assertEquals("dev-1", deviceOwners().get(0).getDeviceId());
    }

    /** LEGACY 与类型化混存（迁移过渡期常态）：同连接两条账目互不干扰。 */
    @Test
    public void legacyAndTypedOwnersCoexist() {
        ModbusSource source = modbusIntegration.register(tcp(1), deviceOwner("entry-1", "dev-1"));
        // LEGACY 串身份经保留的 registerIntegration(String) 包裹入账（公开 register 串形态已物理删除）
        source.registerIntegration("legacy-identity");

        List<ResourceOwner> owners = deviceOwners();
        assertEquals(2, owners.size());
        boolean hasLegacy = false;
        boolean hasDevice = false;
        for (ResourceOwner owner : owners) {
            hasLegacy |= owner.getLevel() == OwnerLevel.LEGACY;
            hasDevice |= owner.getLevel() == OwnerLevel.DEVICE;
        }
        assertTrue(hasLegacy);
        assertTrue(hasDevice);
    }

    /** 同设备重注册同键：账目按 ownerKey 去重（每注册方一视图，owner 集合不重复计）。 */
    @Test
    public void sameDeviceReRegisterDedupsOwnerKey() {
        modbusIntegration.register(tcp(1), deviceOwner("entry-1", "dev-1"));
        modbusIntegration.register(tcp(1), deviceOwner("entry-1", "dev-1"));
        assertEquals("ownerKey 去重", 1, deviceOwners().size());
    }

    // ==================== get*Info 精准查询（§9-10） ====================

    /** 精准命中：设备身份三元组 → 该设备注册的 ModbusInfo（账本持有，非调用方副本）。 */
    @Test
    public void getDeviceInfoPreciseMatchReturnsRegisteredInfo() {
        modbusIntegration.register(tcp(1), deviceOwner("entry-1", "dev-1"));

        ModbusInfo found = modbusIntegration.getDeviceInfo(COORD, "entry-1", "dev-1");
        assertNotNull(found);
        assertEquals(Integer.valueOf(1), found.getSlaveId());
    }

    /** 未注册身份如实 null（不做旗下罗列/推断）。 */
    @Test
    public void getDeviceInfoUnregisteredReturnsNull() {
        modbusIntegration.register(tcp(1), deviceOwner("entry-1", "dev-1"));
        assertNull(modbusIntegration.getDeviceInfo(COORD, "entry-1", "dev-unknown"));
        assertNull(modbusIntegration.getDeviceInfo(COORD, "entry-unknown", "dev-1"));
        assertNull(modbusIntegration.getDeviceInfo("com.ecat:integration-unknown", "entry-1", "dev-1"));
    }

    /** 一名多资源=异常形态：同设备身份注册两连接，精准查询明确异常不猜。 */
    @Test
    public void getDeviceInfoOneIdentityTwoConnectionsThrows() {
        modbusIntegration.register(new ModbusTcpInfo("192.168.1.100", 502, 1), deviceOwner("entry-1", "dev-1"));
        modbusIntegration.register(new ModbusTcpInfo("192.168.1.101", 502, 1), deviceOwner("entry-1", "dev-1"));

        try {
            modbusIntegration.getDeviceInfo(COORD, "entry-1", "dev-1");
            fail("一名多资源应明确抛 IllegalStateException");
        } catch (IllegalStateException expected) {
            // 严格模式：不猜不取首笔
        }
    }

    /** ENTRY/INTEGRATION 层各查自己（层级精准；设备注册不算 entry 本体注册）。 */
    @Test
    public void entryAndIntegrationLevelPreciseQueries() {
        modbusIntegration.register(tcp(1), deviceOwner("entry-1", "dev-1"));
        modbusIntegration.register(tcp(2), ResourceOwner.entry(COORD, "entry-2"));
        modbusIntegration.register(tcp(3), ResourceOwner.integration(COORD));

        assertNotNull(modbusIntegration.getEntryInfo(COORD, "entry-2"));
        assertNull("DEVICE 注册不冒充 entry 本体注册", modbusIntegration.getEntryInfo(COORD, "entry-1"));
        assertNotNull(modbusIntegration.getIntegrationInfo(COORD));
        assertNull(modbusIntegration.getIntegrationInfo("com.ecat:integration-other"));
    }

    // ==================== registerForDevice（消费方一步得源） ====================

    /** 命中：追加消费方 owner 入账 + 宿主绑定收尾摘账（同键挂同一共享资源）。 */
    @Test
    public void registerForDeviceHitAppendsConsumerOwnerAndBindsHost() {
        modbusIntegration.register(tcp(1), deviceOwner("entry-1", "dev-1"));
        RecordingHost host = new RecordingHost();

        ModbusSource view = modbusIntegration.registerForDevice(COORD, "entry-1", "dev-1",
                ResourceOwner.entry(COORD, "entry-task"), host);
        assertNotNull("同键挂上同一共享资源，返回可用视图", view);
        assertEquals("常住人口 +1（设备 + 消费方）", 2, deviceOwners().size());
        assertEquals("宿主绑定一个收尾动作", 1, host.actions.size());

        host.actions.get(0).run();
        assertEquals("收尾自动摘账（只摘消费方条目）", 1, deviceOwners().size());
    }

    /** 未注册设备如实 null：不凭 Info 另开资源。 */
    @Test
    public void registerForDeviceUnknownDeviceReturnsNull() {
        RecordingHost host = new RecordingHost();
        assertNull(modbusIntegration.registerForDevice(COORD, "entry-1", "dev-none",
                ResourceOwner.entry(COORD, "entry-task"), host));
        assertEquals("未命中不绑宿主动作", 0, host.actions.size());
    }

    // ==================== get*Owners 折叠 ====================

    /** 折叠语义：device→entry 去重、entry/integration→integration 去重；LEGACY 透传。 */
    @Test
    public void ownerFoldingByLevel() {
        ModbusSource shared = modbusIntegration.register(tcp(1), deviceOwner("entry-1", "dev-1"));
        modbusIntegration.register(tcp(2), deviceOwner("entry-1", "dev-2"));
        modbusIntegration.register(tcp(3), deviceOwner("entry-2", "dev-3"));
        // LEGACY 串身份经 registerIntegration(String) 包裹入账（公开 register 串形态已物理删除）
        shared.registerIntegration("legacy-identity");
        ResourceRef ref = new ResourceRef(ResourceKind.MODBUS_CONNECTION, CONN);

        assertEquals("全量 4 条（3 DEVICE + 1 LEGACY）", 4, modbusIntegration.getDeviceOwners(ref).size());
        assertEquals("device 折叠到 entry 去重 2 + LEGACY 透传 1", 3, modbusIntegration.getEntryOwners(ref).size());
        assertEquals("折叠到集成 1 + LEGACY 透传 1", 2, modbusIntegration.getIntegrationOwners(ref).size());
    }

    /** refKind 守卫：非 MODBUS_CONNECTION 的 ref 属调用方错误，明确异常不猜。 */
    @Test
    public void wrongResourceKindRejected() {
        ResourceRef wrong = new ResourceRef(ResourceKind.SERIAL_PORT, CONN);
        try {
            modbusIntegration.getDeviceOwners(wrong);
            fail("非 MODBUS_CONNECTION ref 应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 严格模式
        }
    }
}
