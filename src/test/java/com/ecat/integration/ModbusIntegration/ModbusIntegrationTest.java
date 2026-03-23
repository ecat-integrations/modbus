package com.ecat.integration.ModbusIntegration;

import com.ecat.core.Integration.IntegrationManager;
import com.ecat.core.Integration.IntegrationRegistry;
import com.ecat.core.Utils.DynamicConfig.ConfigDefinition;
import com.ecat.core.Utils.TestTools;
import com.ecat.integration.SerialIntegration.SerialIntegration;
import com.ecat.integration.SerialIntegration.SerialSource;
import org.junit.*;
import org.mockito.*;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.ip.IpParameters;
import java.lang.reflect.Method;
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
        if (factoryMock != null) {
            factoryMock.close();
        }
        if (modbusFactoryConstruction != null) {
            modbusFactoryConstruction.close();
        }
        mockitoCloseable.close();
    }

    public Object invokePrivateMethod(Object target, String methodName, Object... args) throws Exception {
        Class<?>[] parameterTypes = new Class[args.length];
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Short) {
                parameterTypes[i] = short.class;
            } else if (args[i] instanceof Integer) {
                parameterTypes[i] = int.class;
            } else if (args[i] instanceof ModbusInfo) {
                parameterTypes[i] = ModbusInfo.class;
            } else if (args[i] instanceof String) {
                parameterTypes[i] = String.class;
            } else {
                parameterTypes[i] = args[i].getClass();
            }
        }
        Method method = TestTools.findMethod(target.getClass(), methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
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
        String identity = "tcp1";

        modbusIntegration.onInit();

        // 使用 TestTools 调用私有方法 createOrGetSource
        try {
            ModbusSource result = (ModbusSource) invokePrivateMethod(modbusIntegration, "createOrGetSource", info, identity);
            assertNotNull(result);
            assertTrue(result instanceof DeviceSpecificModbusSource);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testRegister_andGetSource_Serial() {
        ModbusSerialInfo info = mock(ModbusSerialInfo.class);
        when(info.getPortName()).thenReturn("COM1");
        when(info.getProtocol()).thenReturn(ModbusProtocol.SERIAL);
        String identity = "serial1";

        modbusIntegration.onInit();

        // 直接调用公共方法 register
        ModbusSource result = modbusIntegration.register(info, identity);
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
    public void testRegister_serialFallback_whenSerialIntegrationNull() {
        // Don't set serialIntegration — it defaults to null, should fallback to old mode
        modbusIntegration.onInit();

        ModbusSerialInfo info = new ModbusSerialInfo("COM1", 9600, 8, 1, 0, 1000, 1);

        // Should not throw — falls back to old mode (direct serial port)
        ModbusSource result = modbusIntegration.register(info, "serial-fallback-1");
        assertNotNull("register should return non-null even without serial integration", result);
        assertTrue(result instanceof DeviceSpecificModbusSource);
    }

    @Test
    public void testRegister_serial_withSerialIntegration() throws Exception {
        // Set up mock serial integration via integrationRegistry
        when(integrationRegistry.getIntegration("integration-serial")).thenReturn(mockSerialIntegration);
        when(mockSerialIntegration.register(any(com.ecat.integration.SerialIntegration.SerialInfo.class), anyString()))
            .thenReturn(mockSerialSource);
        when(mockSerialSource.getTimeout()).thenReturn(1000);

        modbusIntegration.onInit();

        ModbusSerialInfo info = new ModbusSerialInfo("COM1", 9600, 8, 1, 0, 1000, 1);

        ModbusSource result = modbusIntegration.register(info, "serial-new-1");

        assertNotNull("register should return non-null", result);
        assertTrue(result instanceof DeviceSpecificModbusSource);
        DeviceSpecificModbusSource deviceSource = (DeviceSpecificModbusSource) result;
        assertEquals(Integer.valueOf(1), deviceSource.getDeviceSlaveId());
        // Verify serial integration was called with correct identity prefix
        verify(mockSerialIntegration).register(
            any(com.ecat.integration.SerialIntegration.SerialInfo.class),
            eq("modbus-COM1"));
    }

    @Test
    public void testRegister_serial_convertSerialInfo() throws Exception {
        // Set up mock serial integration via integrationRegistry
        when(integrationRegistry.getIntegration("integration-serial")).thenReturn(mockSerialIntegration);
        when(mockSerialIntegration.register(any(com.ecat.integration.SerialIntegration.SerialInfo.class), anyString()))
            .thenReturn(mockSerialSource);
        when(mockSerialSource.getTimeout()).thenReturn(500);

        modbusIntegration.onInit();

        ModbusSerialInfo info = new ModbusSerialInfo("/dev/ttyUSB0", 19200, 8, 2, 2, 500, 5);

        modbusIntegration.register(info, "device-5");

        // Verify the conversion happened correctly
        ArgumentCaptor<com.ecat.integration.SerialIntegration.SerialInfo> captor =
            ArgumentCaptor.forClass(com.ecat.integration.SerialIntegration.SerialInfo.class);
        verify(mockSerialIntegration).register(captor.capture(), eq("modbus-/dev/ttyUSB0"));

        com.ecat.integration.SerialIntegration.SerialInfo captured = captor.getValue();
        assertNotNull(captured);
        // Verify port name and baudrate were transferred correctly
        assertTrue(captured.toString().contains("/dev/ttyUSB0"));
        assertTrue(captured.toString().contains("19200"));
    }

    @Test
    public void testRegister_tcp_returnsDeviceSpecificModbusSource() {
        modbusIntegration.onInit();

        ModbusTcpInfo info = new ModbusTcpInfo("192.168.1.100", 502, 1);

        ModbusSource result = modbusIntegration.register(info, "tcp-device1");

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

        ModbusSource source1 = modbusIntegration.register(info1, "device1");
        ModbusSource source2 = modbusIntegration.register(info2, "device2");

        // Both sources should share the same underlying delegate (same getModbusInfo)
        assertNotNull(source1.getModbusInfo());
        assertSame("Sources should share the same underlying ModbusSource", source1.getModbusInfo(), source2.getModbusInfo());
    }

    @Test
    public void testRegister_tcp_createsDifferentSourcesForDifferentConnections() {
        modbusIntegration.onInit();

        ModbusTcpInfo info1 = new ModbusTcpInfo("192.168.1.100", 502, 1);
        ModbusTcpInfo info2 = new ModbusTcpInfo("192.168.1.200", 502, 1);

        ModbusSource source1 = modbusIntegration.register(info1, "device1");
        ModbusSource source2 = modbusIntegration.register(info2, "device2");

        // Sources should have different underlying delegates
        assertNotSame("Sources should have different underlying ModbusSources", source1.getModbusInfo(), source2.getModbusInfo());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testOnRelease_cleansUpSources() throws Exception {
        modbusIntegration.onInit();

        // Register a TCP source to populate tcpSources map
        ModbusTcpInfo info = new ModbusTcpInfo("192.168.1.100", 502, 1);
        modbusIntegration.register(info, "device1");

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
}
