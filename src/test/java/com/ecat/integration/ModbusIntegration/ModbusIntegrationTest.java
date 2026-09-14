package com.ecat.integration.ModbusIntegration;

import com.ecat.core.CommTrace.ResourceOwner;
import com.ecat.core.CommTrace.Usage;
import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Integration.IntegrationManager;
import com.ecat.core.Integration.IntegrationRegistry;
import com.ecat.core.Utils.DynamicConfig.ConfigDefinition;
import com.ecat.core.Utils.TestTools;
import com.ecat.integration.ModbusIntegration.Sdk.ModbusSdkTimers;
import com.ecat.integration.SerialIntegration.SerialIntegration;
import com.ecat.integration.SerialIntegration.SerialInfo;
import com.ecat.integration.SerialIntegration.SerialSource;
import org.junit.*;
import org.mockito.*;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.ip.IpParameters;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.MockedConstruction;

/**
 * ModbusIntegration 单元测试
 * 覆盖初始化、注册、资源释放等核心功能
 * @author coffee
 */
public class ModbusIntegrationTest {

    @Mock
    private IntegrationManager integrationManager;
    @Mock
    private IntegrationRegistry integrationRegistry;
    @Mock
    private ModbusSource modbusSource;
    @Mock
    private ModbusTcpInfo tcpInfo;
    @Mock
    private ModbusSerialInfo serialInfo;
    @Mock
    private ConfigDefinition mockConfigDef;
    @Mock
    private SerialIntegration mockSerialIntegration;
    @Mock
    private SerialSource mockSerialSource;

    @InjectMocks
    private ModbusIntegration modbusIntegration;

    private AutoCloseable mockitoCloseable;
    private org.mockito.MockedStatic<ModbusMasterFactory> factoryMock;
    private MockedConstruction<ModbusFactory> modbusFactoryConstruction;

    private static final String COORD = "com.ecat:integration-modbus-test";

    private static ResourceOwner deviceOwner(String deviceId) {
        return ResourceOwner.device(COORD, "entry-1", deviceId);
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

    @Before
    public void setUp() {
        mockitoCloseable = MockitoAnnotations.openMocks(this);
        modbusIntegration = new ModbusIntegration();

        // mock ModbusMasterFactory.createModbusMaster 和 ModbusMaster.init
        factoryMock = Mockito.mockStatic(ModbusMasterFactory.class);
        ModbusMaster mockMaster = mock(ModbusMaster.class);
        try {
            doNothing().when(mockMaster).init();
        } catch (com.serotonin.modbus4j.exception.ModbusInitException e) {
            // ignore for mock
        }
        when(mockMaster.isInitialized()).thenReturn(true);
        factoryMock.when(() -> ModbusMasterFactory.createModbusMaster(any(ModbusInfo.class))).thenReturn(mockMaster);
        factoryMock.when(() -> ModbusMasterFactory.createSerialMaster(any(ModbusSerialInfo.class), any(SerialSource.class))).thenReturn(mockMaster);

        // mock ModbusFactory constructor (used internally by ModbusMasterFactory)
        final ModbusMaster finalMockMaster = mockMaster;
        modbusFactoryConstruction = Mockito.mockConstruction(ModbusFactory.class,
            (mock, context) -> {
                try {
                    when(mock.createTcpMaster(any(IpParameters.class), anyBoolean())).thenReturn(finalMockMaster);
                } catch (Exception e) {
                    // ignore for mock
                }
            });

        // 使用 TestTools 设置 integrationManager 和 integrationRegistry
        try {
            TestTools.setPrivateField(modbusIntegration, "integrationManager", integrationManager);
            TestTools.setPrivateField(modbusIntegration, "integrationRegistry", integrationRegistry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @After
    public void tearDown() throws Exception {
        // 清理 ModbusIntegration 资源
        if (modbusIntegration != null) {
            modbusIntegration.onPause();
            modbusIntegration.onRelease();
        }
        // onRelease 已把两池置终端态（R-F：停机后取用 REE）——本 JVM 后续测试类还要
        // 惰性建池，测试基建层面复位（生产无此路径，remove 后必是新 JVM）
        ModbusIoPool.resetForTest();
        ModbusSdkTimers.resetForTest();
        if (factoryMock != null) {
            factoryMock.close();
        }
        if (modbusFactoryConstruction != null) {
            modbusFactoryConstruction.close();
        }
        mockitoCloseable.close();
    }

    @Test
    public void testOnInit_withValidConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("max_waiters", 5);
        config.put("wait_timeout", 2000);
        when(integrationManager.loadConfig(anyString())).thenReturn(config);

        modbusIntegration.onInit();

        assertEquals(Integer.valueOf(5), modbusIntegration.maxWaiters);
        assertEquals(Integer.valueOf(2000), modbusIntegration.waitTimeoutMs);
    }

    @Test
    public void testOnInit_withInvalidConfig() {
        Map<String, Object> config = new HashMap<>();
        when(integrationManager.loadConfig(anyString())).thenReturn(config);

        modbusIntegration.onInit();

        assertEquals(Integer.valueOf(Const.DEFAULT_MAX_WAITERS), modbusIntegration.maxWaiters);
        assertEquals(Integer.valueOf(Const.DEFAULT_WAIT_TIMEOUT_MS), modbusIntegration.waitTimeoutMs);
    }

    @Test
    public void testRegister_andGetSource_TCP() {
        ModbusTcpInfo info = mock(ModbusTcpInfo.class);
        when(info.getIpAddress()).thenReturn("localhost");
        when(info.getPort()).thenReturn(502);
        when(info.getProtocol()).thenReturn(ModbusProtocol.TCP);

        modbusIntegration.onInit();

        // 经 host 收口注册入口（内部即 createOrGetSource；私有签名已随 owner 化改形，
        // 反射直达不可维护）
        try {
            ModbusSource result = modbusIntegration.register(info, new HostDevice("entry-1"));
            assertNotNull(result);
            assertTrue(result instanceof DeviceSpecificModbusSource);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 死 source 复用 bug 根源场景回归（bug-record-20260728-170000）。
     * 场景：旧设备释放同连接的共享 source（closeModbus→destroyResources→destroyed 置位
     * （P2 后死源显式标志，旧实现为 executor.shutdown），modbus4j master.isInitialized 仍 true、
     * source 留 map），新设备再申请同连接。
     * 期望：建新源（不复用死源）、死源从 map 清除（无脏数据）、新源 executor 活跃可读。
     * fix 前：isModbusOpen(dead)=true（stale）→ createOrGetSource 死源清理（!isModbusOpen）失效 → 复用死源
     *        → readAndUpdate 提交到已销毁的死源永不执行 → 无数据，须重启 core 才恢复。
     */
    @SuppressWarnings("unchecked")
    @Test
    public void createOrGetSource_deadSourceReplacedOnReregister_sameConnection() throws Exception {
        modbusIntegration.onInit();

        // --- 构造"死源"：曾打开（master.isInitialized=true）后 destroyResources（destroyed 置位）---
        ModbusTcpInfo info = mock(ModbusTcpInfo.class);
        when(info.getIpAddress()).thenReturn("127.0.0.1");
        when(info.getPort()).thenReturn(1699);
        when(info.getProtocol()).thenReturn(ModbusProtocol.TCP);
        ModbusSource deadSource = new ModbusSource(info, 2, 1000, true, false);  // skipOpen=true 跳过 openModbus
        ModbusMaster deadMaster = mock(ModbusMaster.class);
        when(deadMaster.isInitialized()).thenReturn(true);   // 模拟 destroy 后 stale flag 仍 true（modbus4j 既知）
        TestTools.setPrivateField(deadSource, "modbusMaster", deadMaster);
        assertTrue("死源销毁前应 open", deadSource.isModbusOpen());
        deadSource.destroyResources();   // mock master.destroy 无副作用；destroyed 置位
        // fix 后 isModbusOpen(dead)=false；fix 前仍 true（bug 根源）

        // 模拟"前一个设备释放后死源仍留 map"
        Map<String, ModbusSource> tcpSources = (Map<String, ModbusSource>) TestTools.getPrivateField(modbusIntegration, "tcpSources");
        tcpSources.put("127.0.0.1:1699", deadSource);

        // 新设备申请同一连接（经 owner 重载注册入口，内部即 createOrGetSource）
        ModbusSource result = modbusIntegration.register(info, deviceOwner("new-dev-1"));
        assertNotNull(result);

        // --- 断言：脏数据清除 + 新源可用 ---
        assertFalse("死源必须从 map 移除（脏数据妥善清除，不残留）", tcpSources.containsValue(deadSource));
        ModbusSource freshShared = tcpSources.get("127.0.0.1:1699");
        assertNotSame("同连接再申请必须建新源，不得复用死源", deadSource, freshShared);
        assertNotNull(freshShared);
        assertTrue("新源须 open（可读）", freshShared.isModbusOpen());
        java.util.concurrent.ExecutorService freshIoPool =
                (java.util.concurrent.ExecutorService) TestTools.getPrivateField(freshShared, "ioExecutor");
        assertNotNull(freshIoPool);
        assertFalse("新源 IO 旁池必须活跃（池归集成生命周期管理，与单源生死无关）", freshIoPool.isShutdown());
    }

    @Test
    public void testRegister_andGetSource_Serial() {
        // Set up mock serial integration（RTU 带主转发：owner 重载承载）
        when(integrationRegistry.getIntegration("integration-serial")).thenReturn(mockSerialIntegration);
        when(mockSerialIntegration.register(any(SerialInfo.class), any(ResourceOwner.class)))
            .thenReturn(mockSerialSource);

        ModbusSerialInfo info = mock(ModbusSerialInfo.class);
        when(info.getPortName()).thenReturn("COM1");
        when(info.getProtocol()).thenReturn(ModbusProtocol.SERIAL);

        modbusIntegration.onInit();

        // 直接调用公共方法 register（host 收口入口）
        ModbusSource result = modbusIntegration.register(info, new HostDevice("entry-1"));
        assertNotNull(result);
        assertTrue(result instanceof DeviceSpecificModbusSource);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testOnRelease() {
        ModbusSource tcpSource = mock(ModbusSource.class);
        ModbusSource serialSource = mock(ModbusSource.class);

        // 使用 TestTools 获取 tcpSources 和 serialSources
        try {
            Map<String, ModbusSource> tcpSources = (Map<String, ModbusSource>) TestTools.getPrivateField(modbusIntegration, "tcpSources");
            Map<String, ModbusSource> serialSources = (Map<String, ModbusSource>) TestTools.getPrivateField(modbusIntegration, "serialSources");

            tcpSources.put("tcp-device-1", tcpSource);
            serialSources.put("serial-device-1", serialSource);

            modbusIntegration.onRelease();

            // 验证 onRelease 正确调用 destroyResources() 销毁底层资源
            verify(tcpSource, times(1)).destroyResources();
            verify(serialSource, times(1)).destroyResources();
            assertTrue(tcpSources.isEmpty());
            assertTrue(serialSources.isEmpty());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== Unified register() API Tests ====================

    @Test
    public void testRegister_serialThrows_whenSerialIntegrationNull() {
        // Don't set serialIntegration — it defaults to null, should throw
        modbusIntegration.onInit();

        ModbusSerialInfo info = new ModbusSerialInfo("COM1", 9600, 8, 1, 0, 1000, 1);

        // Should throw — RTU requires serial integration (no more fallback)
        try {
            modbusIntegration.register(info, deviceOwner("serial-fallback-1"));
            fail("Should throw IllegalStateException when serial integration is null");
        } catch (IllegalStateException e) {
            // expected
        }
    }

    @Test
    public void testRegister_serial_withSerialIntegration() throws Exception {
        // Set up mock serial integration via integrationRegistry
        when(integrationRegistry.getIntegration("integration-serial")).thenReturn(mockSerialIntegration);
        when(mockSerialIntegration.register(any(SerialInfo.class), any(ResourceOwner.class)))
            .thenReturn(mockSerialSource);
        when(mockSerialSource.getTimeout()).thenReturn(1000);

        modbusIntegration.onInit();

        ModbusSerialInfo info = new ModbusSerialInfo("COM1", 9600, 8, 1, 0, 1000, 1);

        ModbusSource result = modbusIntegration.register(info, deviceOwner("serial-new-1"));

        assertNotNull("register should return non-null", result);
        assertTrue(result instanceof DeviceSpecificModbusSource);
        DeviceSpecificModbusSource deviceSource = (DeviceSpecificModbusSource) result;
        assertEquals(Integer.valueOf(1), deviceSource.getDeviceSlaveId());
        // RTU 串口注册经 ADAPTER 带主转发（§4）：转发调用方 owner 的 adapter 视图
        ArgumentCaptor<ResourceOwner> ownerCaptor = ArgumentCaptor.forClass(ResourceOwner.class);
        verify(mockSerialIntegration).register(any(SerialInfo.class), ownerCaptor.capture());
        assertEquals(Usage.ADAPTER, ownerCaptor.getValue().getUsage());
        assertEquals("转发调用方 owner（穿透保留设备身份）", "serial-new-1", ownerCaptor.getValue().getDeviceId());
    }

    @Test
    public void testRegister_serial_convertSerialInfo() throws Exception {
        // Set up mock serial integration via integrationRegistry
        when(integrationRegistry.getIntegration("integration-serial")).thenReturn(mockSerialIntegration);
        when(mockSerialIntegration.register(any(SerialInfo.class), any(ResourceOwner.class)))
            .thenReturn(mockSerialSource);
        when(mockSerialSource.getTimeout()).thenReturn(500);

        modbusIntegration.onInit();

        ModbusSerialInfo info = new ModbusSerialInfo("/dev/ttyUSB0", 19200, 8, 2, 2, 500, 5);

        modbusIntegration.register(info, deviceOwner("device-5"));

        // Verify the conversion happened correctly
        ArgumentCaptor<SerialInfo> captor = ArgumentCaptor.forClass(SerialInfo.class);
        ArgumentCaptor<ResourceOwner> ownerCaptor = ArgumentCaptor.forClass(ResourceOwner.class);
        verify(mockSerialIntegration).register(captor.capture(), ownerCaptor.capture());

        SerialInfo captured = captor.getValue();
        assertNotNull(captured);
        // Verify port name and baudrate were transferred correctly
        assertTrue(captured.toString().contains("/dev/ttyUSB0"));
        assertTrue(captured.toString().contains("19200"));
        assertEquals(Usage.ADAPTER, ownerCaptor.getValue().getUsage());
    }

    @Test
    public void testRegister_tcp_returnsDeviceSpecificModbusSource() {
        modbusIntegration.onInit();

        ModbusTcpInfo info = new ModbusTcpInfo("192.168.1.100", 502, 1);

        ModbusSource result = modbusIntegration.register(info, deviceOwner("tcp-device1"));

        assertNotNull("register should return non-null", result);
        assertTrue(result instanceof DeviceSpecificModbusSource);
        DeviceSpecificModbusSource deviceSource = (DeviceSpecificModbusSource) result;
        assertEquals(Integer.valueOf(1), deviceSource.getDeviceSlaveId());
    }

    @Test
    public void testRegister_tcp_reusesSourceForSameConnection() {
        modbusIntegration.onInit();

        ModbusTcpInfo info1 = new ModbusTcpInfo("192.168.1.100", 502, 1);
        ModbusTcpInfo info2 = new ModbusTcpInfo("192.168.1.100", 502, 2);

        ModbusSource source1 = modbusIntegration.register(info1, deviceOwner("device1"));
        ModbusSource source2 = modbusIntegration.register(info2, deviceOwner("device2"));

        // Both sources should share the same underlying delegate (same getModbusInfo)
        assertNotNull(source1.getModbusInfo());
        assertSame("Sources should share the same underlying ModbusSource", source1.getModbusInfo(), source2.getModbusInfo());
    }

    @Test
    public void testRegister_tcp_createsDifferentSourcesForDifferentConnections() {
        modbusIntegration.onInit();

        ModbusTcpInfo info1 = new ModbusTcpInfo("192.168.1.100", 502, 1);
        ModbusTcpInfo info2 = new ModbusTcpInfo("192.168.1.200", 502, 1);

        ModbusSource source1 = modbusIntegration.register(info1, deviceOwner("device1"));
        ModbusSource source2 = modbusIntegration.register(info2, deviceOwner("device2"));

        // Sources should have different underlying delegates
        assertNotSame("Sources should have different underlying ModbusSources", source1.getModbusInfo(), source2.getModbusInfo());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testOnRelease_cleansUpSources() throws Exception {
        modbusIntegration.onInit();

        // Register a TCP source to populate tcpSources map
        ModbusTcpInfo info = new ModbusTcpInfo("192.168.1.100", 502, 1);
        modbusIntegration.register(info, deviceOwner("device1"));

        Map<String, ModbusSource> tcpSources = (Map<String, ModbusSource>) TestTools.getPrivateField(modbusIntegration, "tcpSources");
        Map<String, ModbusSource> serialSources = (Map<String, ModbusSource>) TestTools.getPrivateField(modbusIntegration, "serialSources");
        assertFalse("tcpSources should not be empty before release", tcpSources.isEmpty());

        modbusIntegration.onRelease();

        assertTrue("tcpSources should be empty after release", tcpSources.isEmpty());
        assertTrue("serialSources should be empty after release", serialSources.isEmpty());
    }

    @Test
    public void testOnInit_serialIntegrationNotFound() {
        // integrationRegistry returns null for serial integration
        when(integrationRegistry.getIntegration(anyString())).thenReturn(null);
        modbusIntegration.onInit();

        try {
            Object serialIntegration = TestTools.getPrivateField(modbusIntegration, "serialIntegration");
            assertNull("serialIntegration should be null when not found", serialIntegration);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testOnInit_serialIntegrationFound() {
        when(integrationRegistry.getIntegration("integration-serial")).thenReturn(mockSerialIntegration);
        modbusIntegration.onInit();

        try {
            Object serialIntegration = TestTools.getPrivateField(modbusIntegration, "serialIntegration");
            assertSame("serialIntegration should be the mocked instance", mockSerialIntegration, serialIntegration);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== Protocol Conflict Detection Tests ====================

    @Test
    public void testRegister_tcpAndRtuOverTcp_sameConnection_throwsProtocolConflict() {
        modbusIntegration.onInit();

        // Register first device with TCP (MBAP) protocol
        ModbusTcpInfo tcpInfo = new ModbusTcpInfo("192.168.1.100", 502, 1);
        ModbusSource source1 = modbusIntegration.register(tcpInfo, deviceOwner("tcp-device1"));
        assertNotNull(source1);

        // Try to register second device with RTU_OVER_TCP on same ip:port
        ModbusTcpInfo rtuInfo = new ModbusTcpInfo("192.168.1.100", 502, 1, ModbusProtocol.RTU_OVER_TCP);
        try {
            modbusIntegration.register(rtuInfo, deviceOwner("rtu-device1"));
            fail("Should throw IllegalStateException for protocol conflict");
        } catch (IllegalStateException e) {
            assertTrue("Exception message should mention protocol conflict",
                e.getMessage().contains("协议冲突"));
            assertTrue("Exception message should contain connection identity",
                e.getMessage().contains("192.168.1.100:502"));
            assertTrue("Exception message should mention existing protocol",
                e.getMessage().contains("TCP"));
            assertTrue("Exception message should mention new protocol",
                e.getMessage().contains("RTU_OVER_TCP"));
        }
    }

    @Test
    public void testRegister_rtuOverTcpAndTcp_sameConnection_throwsProtocolConflict() {
        modbusIntegration.onInit();

        // Register first device with RTU_OVER_TCP protocol
        ModbusTcpInfo rtuInfo = new ModbusTcpInfo("192.168.1.100", 502, 1, ModbusProtocol.RTU_OVER_TCP);
        ModbusSource source1 = modbusIntegration.register(rtuInfo, deviceOwner("rtu-device1"));
        assertNotNull(source1);

        // Try to register second device with TCP on same ip:port
        ModbusTcpInfo tcpInfo = new ModbusTcpInfo("192.168.1.100", 502, 1);
        try {
            modbusIntegration.register(tcpInfo, deviceOwner("tcp-device1"));
            fail("Should throw IllegalStateException for protocol conflict");
        } catch (IllegalStateException e) {
            assertTrue("Exception message should mention protocol conflict",
                e.getMessage().contains("协议冲突"));
            assertTrue("Exception message should mention RTU_OVER_TCP as existing",
                e.getMessage().contains("RTU_OVER_TCP"));
            assertTrue("Exception message should mention TCP as new",
                e.getMessage().contains("TCP"));
        }
    }

    @Test
    public void testRegister_sameRtuOverTcpProtocol_sameConnection_succeeds() {
        modbusIntegration.onInit();

        // Register two devices with same RTU_OVER_TCP protocol on same connection
        ModbusTcpInfo rtuInfo1 = new ModbusTcpInfo("192.168.1.100", 502, 1, ModbusProtocol.RTU_OVER_TCP);
        ModbusTcpInfo rtuInfo2 = new ModbusTcpInfo("192.168.1.100", 502, 2, ModbusProtocol.RTU_OVER_TCP);

        ModbusSource source1 = modbusIntegration.register(rtuInfo1, deviceOwner("rtu-device1"));
        ModbusSource source2 = modbusIntegration.register(rtuInfo2, deviceOwner("rtu-device2"));

        assertNotNull(source1);
        assertNotNull(source2);
        // Both should share the same underlying source
        assertSame("RTU_OVER_TCP sources should share the same underlying source",
            source1.getModbusInfo(), source2.getModbusInfo());
        assertEquals(ModbusProtocol.RTU_OVER_TCP, source1.getModbusInfo().getProtocol());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testRegister_differentProtocol_afterSourceDestroyed_succeeds() throws Exception {
        modbusIntegration.onInit();

        // Register TCP device
        ModbusTcpInfo tcpInfo = new ModbusTcpInfo("192.168.1.100", 502, 1);
        modbusIntegration.register(tcpInfo, deviceOwner("tcp-device1"));

        // Get the shared source and simulate it being destroyed (isModbusOpen=false)
        Map<String, ModbusSource> tcpSources = (Map<String, ModbusSource>)
            TestTools.getPrivateField(modbusIntegration, "tcpSources");
        ModbusSource sharedSource = tcpSources.get("192.168.1.100:502");
        assertNotNull("Shared source should exist", sharedSource);

        // Replace with mock that simulates destroyed state
        ModbusSource destroyedSource = mock(ModbusSource.class);
        when(destroyedSource.isModbusOpen()).thenReturn(false);
        when(destroyedSource.getModbusInfo()).thenReturn(tcpInfo);
        tcpSources.put("192.168.1.100:502", destroyedSource);

        // Now register RTU_OVER_TCP - should succeed because destroyed source gets cleaned up
        ModbusTcpInfo rtuInfo = new ModbusTcpInfo("192.168.1.100", 502, 1, ModbusProtocol.RTU_OVER_TCP);
        ModbusSource source2 = modbusIntegration.register(rtuInfo, deviceOwner("rtu-device1"));

        assertNotNull("Should successfully register after source destroyed", source2);
        // The new source should have RTU_OVER_TCP protocol
        assertEquals(ModbusProtocol.RTU_OVER_TCP, source2.getModbusInfo().getProtocol());
    }
}
