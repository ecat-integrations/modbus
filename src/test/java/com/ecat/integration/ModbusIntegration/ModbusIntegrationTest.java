package com.ecat.integration.ModbusIntegration;

import com.ecat.core.Integration.IntegrationManager;
import com.ecat.core.Utils.DynamicConfig.ConfigDefinition;
import com.ecat.core.Utils.TestTools;
import org.junit.*;
import org.mockito.*;
import com.serotonin.modbus4j.ModbusMaster;
import java.lang.reflect.Method;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * ModbusIntegration 单元测试
 * 覆盖初始化、注册、资源释放等核心功能
 * @author coffee
 */
public class ModbusIntegrationTest {

    @Mock
    private IntegrationManager integrationManager;
    @Mock
    private ModbusSource modbusSource;
    @Mock
    private ModbusTcpInfo tcpInfo;
    @Mock
    private ModbusSerialInfo serialInfo;
    @Mock
    private ConfigDefinition mockConfigDef;

    @InjectMocks
    private ModbusIntegration modbusIntegration;

    private AutoCloseable mockitoCloseable;
    private org.mockito.MockedStatic<ModbusMasterFactory> factoryMock;

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

        // 使用 TestTools 设置 integrationManager
        try {
            TestTools.setPrivateField(modbusIntegration, "integrationManager", integrationManager);
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

        // when(mockConfigDef.validateConfig(config)).thenReturn(true);

        modbusIntegration.onInit();

        assertEquals(Integer.valueOf(5), modbusIntegration.maxWaiters);
        assertEquals(Integer.valueOf(2000), modbusIntegration.waitTimeoutMs);
    }

    @Test
    public void testOnInit_withInvalidConfig() {
        Map<String, Object> config = new HashMap<>();
        when(integrationManager.loadConfig(anyString())).thenReturn(config);

        // when(mockConfigDef.validateConfig(config)).thenReturn(false);

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

            tcpSources.put("tcp1", tcpSource);
            serialSources.put("serial1", serialSource);

            modbusIntegration.onRelease();

            verify(tcpSource, times(1)).closeModbus();
            verify(serialSource, times(1)).closeModbus();
            assertTrue(tcpSources.isEmpty());
            assertTrue(serialSources.isEmpty());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
