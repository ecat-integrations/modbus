package com.ecat.integration.ModbusIntegration;

import com.ecat.core.Integration.IntegrationManager;
import org.junit.*;
import org.mockito.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.serotonin.modbus4j.ModbusMaster;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * ModbusIntegration多设备共享连接的集成测试
 * - 测试多设备共享连接
   - 验证不同协议类型的处理
   - 测试连接复用逻辑
 * 
 * @author coffee
 */
public class ModbusIntegrationMultiDeviceTest {

    private ModbusIntegration modbusIntegration;
    
    @Mock
    private ModbusSerialInfo mockSerialInfo1;
    
    @Mock
    private ModbusSerialInfo mockSerialInfo2;
    
    @Mock
    private ModbusTcpInfo mockTcpInfo1;
    
    @Mock
    private ModbusTcpInfo mockTcpInfo2;

    private AutoCloseable mockitoCloseable;
    private org.mockito.MockedStatic<ModbusMasterFactory> factoryMock;

    @Before
    public void setUp() throws Exception {
        mockitoCloseable = MockitoAnnotations.openMocks(this);
        modbusIntegration = new ModbusIntegration();

        // mock ModbusMasterFactory.createModbusMaster 和 ModbusMaster.init
        factoryMock = Mockito.mockStatic(ModbusMasterFactory.class);
        ModbusMaster mockMaster = mock(ModbusMaster.class);
        doNothing().when(mockMaster).init();
        factoryMock.when(() -> ModbusMasterFactory.createModbusMaster(any(ModbusInfo.class))).thenReturn(mockMaster);

        // 设置串口设备信息
        when(mockSerialInfo1.getPortName()).thenReturn("COM1");
        when(mockSerialInfo1.getSlaveId()).thenReturn(1);
        when(mockSerialInfo1.getProtocol()).thenReturn(ModbusProtocol.SERIAL);
        when(mockSerialInfo2.getPortName()).thenReturn("COM1");
        when(mockSerialInfo2.getSlaveId()).thenReturn(2);
        when(mockSerialInfo2.getProtocol()).thenReturn(ModbusProtocol.SERIAL);

        // 设置TCP设备信息
        when(mockTcpInfo1.getIpAddress()).thenReturn("192.168.1.100");
        when(mockTcpInfo1.getPort()).thenReturn(502);
        when(mockTcpInfo1.getSlaveId()).thenReturn(1);
        when(mockTcpInfo1.getProtocol()).thenReturn(ModbusProtocol.TCP);
        when(mockTcpInfo2.getIpAddress()).thenReturn("192.168.1.100");
        when(mockTcpInfo2.getPort()).thenReturn(502);
        when(mockTcpInfo2.getSlaveId()).thenReturn(2);
        when(mockTcpInfo2.getProtocol()).thenReturn(ModbusProtocol.TCP);

        // 设置integrationManager mock
        IntegrationManager integrationManager = mock(IntegrationManager.class);
        Map<String, Object> config = new HashMap<>();
        config.put("max_waiters", 5);
        config.put("wait_timeout", 2000);
        when(integrationManager.loadConfig(anyString())).thenReturn(config);

        // 使用反射设置integrationManager
        try {
            java.lang.reflect.Field integrationManagerField = ModbusIntegration.class.getSuperclass().getDeclaredField("integrationManager");
            integrationManagerField.setAccessible(true);
            integrationManagerField.set(modbusIntegration, integrationManager);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set integrationManager", e);
        }

        modbusIntegration.onInit();
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

    @Test
    public void testSerialDevicesShareConnection() {
        // 注册两个共享同一串口的设备
        ModbusSource device1 = modbusIntegration.register(mockSerialInfo1, "serial_device_1");
        ModbusSource device2 = modbusIntegration.register(mockSerialInfo2, "serial_device_2");
        
        // 验证返回的都是DeviceSpecificModbusSource
        assertTrue(device1 instanceof DeviceSpecificModbusSource);
        assertTrue(device2 instanceof DeviceSpecificModbusSource);
        
        DeviceSpecificModbusSource specificDevice1 = (DeviceSpecificModbusSource) device1;
        DeviceSpecificModbusSource specificDevice2 = (DeviceSpecificModbusSource) device2;
        
        // 验证ModbusInfo正确设置
        assertEquals(Integer.valueOf(1), specificDevice1.getDeviceSlaveId());
        assertEquals(Integer.valueOf(2), specificDevice2.getDeviceSlaveId());
        
        // 验证它们共享同一个底层ModbusSource
        ModbusSource delegate1 = getDelegate(specificDevice1);
        ModbusSource delegate2 = getDelegate(specificDevice2);
        assertSame(delegate1, delegate2);
    }

    @Test
    public void testTcpDevicesShareConnection() {
        // 注册两个共享同一TCP连接的设备
        ModbusSource device1 = modbusIntegration.register(mockTcpInfo1, "tcp_device_1");
        ModbusSource device2 = modbusIntegration.register(mockTcpInfo2, "tcp_device_2");
        
        // 验证返回的都是DeviceSpecificModbusSource
        assertTrue(device1 instanceof DeviceSpecificModbusSource);
        assertTrue(device2 instanceof DeviceSpecificModbusSource);
        
        DeviceSpecificModbusSource specificDevice1 = (DeviceSpecificModbusSource) device1;
        DeviceSpecificModbusSource specificDevice2 = (DeviceSpecificModbusSource) device2;
        
        // 验证slaveId正确设置
        assertEquals(Integer.valueOf(1), specificDevice1.getDeviceSlaveId());
        assertEquals(Integer.valueOf(2), specificDevice2.getDeviceSlaveId());
        
        // 验证它们共享同一个底层ModbusSource
        ModbusSource delegate1 = getDelegate(specificDevice1);
        ModbusSource delegate2 = getDelegate(specificDevice2);
        assertSame(delegate1, delegate2);
    }

    @Test
    public void testDifferentSerialPortsCreateDifferentConnections() {
        // 创建不同串口的设备信息
        ModbusSerialInfo mockSerialInfo3 = mock(ModbusSerialInfo.class);
        when(mockSerialInfo3.getPortName()).thenReturn("COM2");
        when(mockSerialInfo3.getSlaveId()).thenReturn(1);
        when(mockSerialInfo3.getProtocol()).thenReturn(ModbusProtocol.SERIAL);
        
        // 注册不同串口的设备
        ModbusSource device1 = modbusIntegration.register(mockSerialInfo1, "serial_device_1");
        ModbusSource device3 = modbusIntegration.register(mockSerialInfo3, "serial_device_3");
        
        DeviceSpecificModbusSource specificDevice1 = (DeviceSpecificModbusSource) device1;
        DeviceSpecificModbusSource specificDevice3 = (DeviceSpecificModbusSource) device3;
        
        // 验证它们使用不同的底层连接
        ModbusSource delegate1 = getDelegate(specificDevice1);
        ModbusSource delegate3 = getDelegate(specificDevice3);
        assertNotSame(delegate1, delegate3);
    }

    @Test
    public void testDifferentTcpConnectionsCreateDifferentConnections() {
        // 创建不同TCP连接的设备信息
        ModbusTcpInfo mockTcpInfo3 = mock(ModbusTcpInfo.class);
        when(mockTcpInfo3.getIpAddress()).thenReturn("192.168.1.101");
        when(mockTcpInfo3.getPort()).thenReturn(502);
        when(mockTcpInfo3.getSlaveId()).thenReturn(1);
        when(mockTcpInfo3.getProtocol()).thenReturn(ModbusProtocol.TCP);
        
        // 注册不同TCP连接的设备
        ModbusSource device1 = modbusIntegration.register(mockTcpInfo1, "tcp_device_1");
        ModbusSource device3 = modbusIntegration.register(mockTcpInfo3, "tcp_device_3");
        
        DeviceSpecificModbusSource specificDevice1 = (DeviceSpecificModbusSource) device1;
        DeviceSpecificModbusSource specificDevice3 = (DeviceSpecificModbusSource) device3;
        
        // 验证它们使用不同的底层连接
        ModbusSource delegate1 = getDelegate(specificDevice1);
        ModbusSource delegate3 = getDelegate(specificDevice3);
        assertNotSame(delegate1, delegate3);
    }

    @Test
    public void testSameDeviceReturnsSameInstance() {
        // 注册同一个设备两次
        ModbusSource device1a = modbusIntegration.register(mockSerialInfo1, "serial_device_1a");
        ModbusSource device1b = modbusIntegration.register(mockSerialInfo1, "serial_device_1b");
        
        // 验证返回的都是DeviceSpecificModbusSource
        assertTrue(device1a instanceof DeviceSpecificModbusSource);
        assertTrue(device1b instanceof DeviceSpecificModbusSource);
        
        DeviceSpecificModbusSource specificDevice1a = (DeviceSpecificModbusSource) device1a;
        DeviceSpecificModbusSource specificDevice1b = (DeviceSpecificModbusSource) device1b;
        
        // 验证它们共享同一个底层ModbusSource
        ModbusSource delegate1a = getDelegate(specificDevice1a);
        ModbusSource delegate1b = getDelegate(specificDevice1b);
        assertSame(delegate1a, delegate1b);
        
        // 验证slaveId正确设置
        assertEquals(Integer.valueOf(1), specificDevice1a.getDeviceSlaveId());
        assertEquals(Integer.valueOf(1), specificDevice1b.getDeviceSlaveId());
    }

    @Test
    public void testLockManagementIsShared() throws Exception {
        // 注册两个共享串口的设备
        ModbusSource device1 = modbusIntegration.register(mockSerialInfo1, "serial_device_1");
        ModbusSource device2 = modbusIntegration.register(mockSerialInfo2, "serial_device_2");
        
        // 确保初始状态：等待计数为0
        assertEquals(0, device1.getWaitingCount());
        assertEquals(0, device2.getWaitingCount());
        
        // 获取锁
        String lockKey1 = device1.acquire();
        assertNotNull("device1 should be able to acquire lock", lockKey1);
        
        // 使用多线程测试锁的并发行为
        CountDownLatch latch = new CountDownLatch(1);
        CompletableFuture<String> device2LockFuture = CompletableFuture.supplyAsync(() -> {
            try {
                latch.await(); // 等待主线程通知
                return device2.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        });
        // 通知device2开始尝试获取锁
        latch.countDown();

        // 验证等待计数增加
        Thread.sleep(500); // 等待异步操作
        assertEquals(1, device2.getWaitingCount());
        
        // 释放锁
        assertTrue("device1 should be able to release lock", device1.release(lockKey1));
        
        
        // 等待device2的获取操作完成（应该返回lockKey2，因为锁被device1释放）
        String lockKey2 = device2LockFuture.get(3, TimeUnit.SECONDS);
        // 清理
        assertTrue("device2 should be able to release lock", device2.release(lockKey2));
    }

    @Test
    public void testWaitingCountIsShared() {
        // 注册两个共享串口的设备
        ModbusSource device1 = modbusIntegration.register(mockSerialInfo1, "serial_device_1");
        ModbusSource device2 = modbusIntegration.register(mockSerialInfo2, "serial_device_2");
        
        // 初始等待计数应该为0
        assertEquals(0, device1.getWaitingCount());
        assertEquals(0, device2.getWaitingCount());
        
        // 获取锁
        String lockKey1 = device1.acquire();
        assertNotNull(lockKey1);
        
        // 验证锁已获取，等待计数仍为0
        assertEquals(0, device1.getWaitingCount());
        assertEquals(0, device2.getWaitingCount());
        
        // 清理
        assertTrue(device1.release(lockKey1));
    }

    @Test
    public void testIntegrationRegistration() {
        // 注册设备
        ModbusSource device1 = modbusIntegration.register(mockSerialInfo1, "serial_device_1");
        ModbusSource device2 = modbusIntegration.register(mockSerialInfo2, "serial_device_2");
        
        // 验证integration被正确注册
        DeviceSpecificModbusSource specificDevice1 = (DeviceSpecificModbusSource) device1;
        DeviceSpecificModbusSource specificDevice2 = (DeviceSpecificModbusSource) device2;
        
        ModbusSource delegate = getDelegate(specificDevice1);
        
        // 验证两个设备的identity都被注册到了同一个delegate上
        // 注意：这里我们无法直接访问registeredIntegrations，但可以通过其他方式验证
        assertNotNull(delegate);
        assertSame(delegate, getDelegate(specificDevice2));
    }

    /**
     * 使用反射获取DeviceSpecificModbusSource的delegate字段
     */
    private ModbusSource getDelegate(DeviceSpecificModbusSource deviceSpecificSource) {
        try {
            java.lang.reflect.Field delegateField = DeviceSpecificModbusSource.class.getDeclaredField("delegate");
            delegateField.setAccessible(true);
            return (ModbusSource) delegateField.get(deviceSpecificSource);
        } catch (Exception e) {
            fail("Failed to access delegate field: " + e.getMessage());
            return null;
        }
    }

    @Test
    public void testUnsupportedProtocolType() {
        // 创建不支持的ModbusInfo类型
        ModbusInfo unsupportedInfo = mock(ModbusInfo.class);
        
        // 验证抛出异常
        try {
            modbusIntegration.register(unsupportedInfo, "unsupported_device");
            fail("Should throw IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // 预期的异常
        }
    }

    @Test
    public void testConnectionIdentityGeneration() {
        // 测试串口连接标识生成
        String serialIdentity = "COM1";
        when(mockSerialInfo1.getPortName()).thenReturn(serialIdentity);
        
        ModbusSource device1 = modbusIntegration.register(mockSerialInfo1, "serial_device_1");
        assertNotNull(device1);
        
        // 测试TCP连接标识生成
        when(mockTcpInfo1.getIpAddress()).thenReturn("192.168.1.100");
        when(mockTcpInfo1.getPort()).thenReturn(502);
        
        ModbusSource tcpDevice1 = modbusIntegration.register(mockTcpInfo1, "tcp_device_1");
        assertNotNull(tcpDevice1);
    }
}
