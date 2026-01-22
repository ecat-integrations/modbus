package com.ecat.integration.ModbusIntegration;

import com.serotonin.modbus4j.msg.*;
import org.junit.*;
import org.mockito.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DeviceSpecificModbusSource的单元测试
 * - 测试装饰器模式的所有功能
 * - 验证slaveId正确传递
 * - 测试锁管理委托
 * 
 * @author coffee
 */
public class DeviceSpecificModbusSourceTest {

    @Mock
    private ModbusSource mockDelegate;
    
    @Mock
    private ModbusTcpInfo mockModbusInfo;
    
    @Mock
    private ReadCoilsResponse mockReadCoilsResponse;
    
    @Mock
    private ReadHoldingRegistersResponse mockReadHoldingRegistersResponse;
    
    @Mock
    private WriteCoilResponse mockWriteCoilResponse;
    
    @Mock
    private WriteRegisterResponse mockWriteRegisterResponse;
    
    private DeviceSpecificModbusSource deviceSpecificSource;
    private static final Integer DEVICE_SLAVE_ID = 2;
    private static final String TEST_IDENTITY = "test_device";

    private AutoCloseable mockitoCloseable;

    @Before
    public void setUp() throws Exception {
        mockitoCloseable = MockitoAnnotations.openMocks(this);
        when(mockDelegate.getModbusInfo()).thenReturn(mockModbusInfo);
        when(mockDelegate.getMaxWaiters()).thenReturn(5);
        when(mockDelegate.getWaitTimeoutMs()).thenReturn(3000);
        when(mockModbusInfo.getSlaveId()).thenReturn(DEVICE_SLAVE_ID);
        
        deviceSpecificSource = new DeviceSpecificModbusSource(mockDelegate, mockModbusInfo);
    }

    @After
    public void tearDown() throws Exception {
        mockitoCloseable.close();
    }

    @Test
    public void testConstructor() {
        assertNotNull(deviceSpecificSource);
        assertEquals(DEVICE_SLAVE_ID, deviceSpecificSource.getDeviceSlaveId());
        verify(mockDelegate, never()).registerIntegration(anyString());
    }

    @Test
    public void testAcquire() {
        String expectedKey = "test_key";
        when(mockDelegate.acquire()).thenReturn(expectedKey);
        
        String result = deviceSpecificSource.acquire();
        
        assertEquals(expectedKey, result);
        verify(mockDelegate).acquire();
    }

    @Test
    public void testAcquireWithTimeout() {
        String expectedKey = "test_key_timeout";
        when(mockDelegate.acquire(anyLong(), any(TimeUnit.class))).thenReturn(expectedKey);
        
        String result = deviceSpecificSource.acquire(5000, TimeUnit.MILLISECONDS);
        
        assertEquals(expectedKey, result);
        verify(mockDelegate).acquire(5000, TimeUnit.MILLISECONDS);
    }

    @Test
    public void testRelease() {
        String releaseKey = "release_key";
        when(mockDelegate.release(releaseKey)).thenReturn(true);
        
        boolean result = deviceSpecificSource.release(releaseKey);
        
        assertTrue(result);
        verify(mockDelegate).release(releaseKey);
    }

    @Test
    public void testGetWaitingCount() {
        int expectedCount = 3;
        when(mockDelegate.getWaitingCount()).thenReturn(expectedCount);
        
        int result = deviceSpecificSource.getWaitingCount();
        
        assertEquals(expectedCount, result);
        verify(mockDelegate).getWaitingCount();
    }

    @Test
    public void testReadCoils() {
        int startAddress = 0;
        int numberOfBits = 10;
        CompletableFuture<ReadCoilsResponse> expectedFuture = CompletableFuture.completedFuture(mockReadCoilsResponse);
        
        when(mockDelegate.readCoilsWithSlaveId(DEVICE_SLAVE_ID, startAddress, numberOfBits))
            .thenReturn(expectedFuture);
        
        CompletableFuture<ReadCoilsResponse> result = deviceSpecificSource.readCoils(startAddress, numberOfBits);
        
        assertEquals(expectedFuture, result);
        verify(mockDelegate).readCoilsWithSlaveId(DEVICE_SLAVE_ID, startAddress, numberOfBits);
    }

    @Test
    public void testReadHoldingRegisters() {
        int startAddress = 0;
        int numberOfRegisters = 5;
        CompletableFuture<ReadHoldingRegistersResponse> expectedFuture = 
            CompletableFuture.completedFuture(mockReadHoldingRegistersResponse);
        
        when(mockDelegate.readHoldingRegistersWithSlaveId(DEVICE_SLAVE_ID, startAddress, numberOfRegisters))
            .thenReturn(expectedFuture);
        
        CompletableFuture<ReadHoldingRegistersResponse> result = 
            deviceSpecificSource.readHoldingRegisters(startAddress, numberOfRegisters);
        
        assertEquals(expectedFuture, result);
        verify(mockDelegate).readHoldingRegistersWithSlaveId(DEVICE_SLAVE_ID, startAddress, numberOfRegisters);
    }

    @Test
    public void testWriteCoil() {
        int address = 1;
        boolean value = true;
        CompletableFuture<WriteCoilResponse> expectedFuture = 
            CompletableFuture.completedFuture(mockWriteCoilResponse);
        
        when(mockDelegate.writeCoilWithSlaveId(DEVICE_SLAVE_ID, address, value))
            .thenReturn(expectedFuture);
        
        CompletableFuture<WriteCoilResponse> result = deviceSpecificSource.writeCoil(address, value);
        
        assertEquals(expectedFuture, result);
        verify(mockDelegate).writeCoilWithSlaveId(DEVICE_SLAVE_ID, address, value);
    }

    @Test
    public void testWriteRegister() {
        int address = 2;
        int value = 1234;
        CompletableFuture<WriteRegisterResponse> expectedFuture = 
            CompletableFuture.completedFuture(mockWriteRegisterResponse);
        
        when(mockDelegate.writeRegisterWithSlaveId(DEVICE_SLAVE_ID, address, value))
            .thenReturn(expectedFuture);
        
        CompletableFuture<WriteRegisterResponse> result = deviceSpecificSource.writeRegister(address, value);
        
        assertEquals(expectedFuture, result);
        verify(mockDelegate).writeRegisterWithSlaveId(DEVICE_SLAVE_ID, address, value);
    }

    @Test
    public void testReadDiscreteInputs() {
        int startAddress = 0;
        int numberOfBits = 8;
        CompletableFuture<ReadDiscreteInputsResponse> expectedFuture = 
            CompletableFuture.completedFuture(mock(ReadDiscreteInputsResponse.class));
        
        when(mockDelegate.readDiscreteInputsWithSlaveId(DEVICE_SLAVE_ID, startAddress, numberOfBits))
            .thenReturn(expectedFuture);
        
        CompletableFuture<ReadDiscreteInputsResponse> result = 
            deviceSpecificSource.readDiscreteInputs(startAddress, numberOfBits);
        
        assertEquals(expectedFuture, result);
        verify(mockDelegate).readDiscreteInputsWithSlaveId(DEVICE_SLAVE_ID, startAddress, numberOfBits);
    }

    @Test
    public void testReadInputRegisters() {
        int startAddress = 0;
        int numberOfRegisters = 3;
        CompletableFuture<ReadInputRegistersResponse> expectedFuture = 
            CompletableFuture.completedFuture(mock(ReadInputRegistersResponse.class));
        
        when(mockDelegate.readInputRegistersWithSlaveId(DEVICE_SLAVE_ID, startAddress, numberOfRegisters))
            .thenReturn(expectedFuture);
        
        CompletableFuture<ReadInputRegistersResponse> result = 
            deviceSpecificSource.readInputRegisters(startAddress, numberOfRegisters);
        
        assertEquals(expectedFuture, result);
        verify(mockDelegate).readInputRegistersWithSlaveId(DEVICE_SLAVE_ID, startAddress, numberOfRegisters);
    }

    @Test
    public void testReadExceptionStatus() {
        CompletableFuture<ReadExceptionStatusResponse> expectedFuture = 
            CompletableFuture.completedFuture(mock(ReadExceptionStatusResponse.class));
        
        when(mockDelegate.readExceptionStatusWithSlaveId(DEVICE_SLAVE_ID))
            .thenReturn(expectedFuture);
        
        CompletableFuture<ReadExceptionStatusResponse> result = deviceSpecificSource.readExceptionStatus();
        
        assertEquals(expectedFuture, result);
        verify(mockDelegate).readExceptionStatusWithSlaveId(DEVICE_SLAVE_ID);
    }

    @Test
    public void testReportSlaveId() {
        CompletableFuture<ReportSlaveIdResponse> expectedFuture = 
            CompletableFuture.completedFuture(mock(ReportSlaveIdResponse.class));
        
        when(mockDelegate.reportSlaveIdWithSlaveId(DEVICE_SLAVE_ID))
            .thenReturn(expectedFuture);
        
        CompletableFuture<ReportSlaveIdResponse> result = deviceSpecificSource.reportSlaveId();
        
        assertEquals(expectedFuture, result);
        verify(mockDelegate).reportSlaveIdWithSlaveId(DEVICE_SLAVE_ID);
    }

    @Test
    public void testWriteCoils() {
        int startAddress = 0;
        boolean[] values = {true, false, true};
        CompletableFuture<WriteCoilsResponse> expectedFuture = 
            CompletableFuture.completedFuture(mock(WriteCoilsResponse.class));
        
        when(mockDelegate.writeCoilsWithSlaveId(DEVICE_SLAVE_ID, startAddress, values))
            .thenReturn(expectedFuture);
        
        CompletableFuture<WriteCoilsResponse> result = deviceSpecificSource.writeCoils(startAddress, values);
        
        assertEquals(expectedFuture, result);
        verify(mockDelegate).writeCoilsWithSlaveId(DEVICE_SLAVE_ID, startAddress, values);
    }

    @Test
    public void testWriteMaskRegister() {
        int address = 1;
        int andMask = 0xFF00;
        int orMask = 0x00FF;
        CompletableFuture<WriteMaskRegisterResponse> expectedFuture = 
            CompletableFuture.completedFuture(mock(WriteMaskRegisterResponse.class));
        
        when(mockDelegate.writeMaskRegisterWithSlaveId(DEVICE_SLAVE_ID, address, andMask, orMask))
            .thenReturn(expectedFuture);
        
        CompletableFuture<WriteMaskRegisterResponse> result = 
            deviceSpecificSource.writeMaskRegister(address, andMask, orMask);
        
        assertEquals(expectedFuture, result);
        verify(mockDelegate).writeMaskRegisterWithSlaveId(DEVICE_SLAVE_ID, address, andMask, orMask);
    }

    @Test
    public void testWriteRegisters() {
        int startAddress = 0;
        short[] values = {1, 2, 3};
        CompletableFuture<WriteRegistersResponse> expectedFuture = 
            CompletableFuture.completedFuture(mock(WriteRegistersResponse.class));
        
        when(mockDelegate.writeRegistersWithSlaveId(DEVICE_SLAVE_ID, startAddress, values))
            .thenReturn(expectedFuture);
        
        CompletableFuture<WriteRegistersResponse> result = deviceSpecificSource.writeRegisters(startAddress, values);
        
        assertEquals(expectedFuture, result);
        verify(mockDelegate).writeRegistersWithSlaveId(DEVICE_SLAVE_ID, startAddress, values);
    }

    @Test
    public void testRegisterIntegration() {
        deviceSpecificSource.registerIntegration(TEST_IDENTITY);
        verify(mockDelegate).registerIntegration(TEST_IDENTITY);
    }

    @Test
    public void testRemoveIntegration() {
        deviceSpecificSource.removeIntegration(TEST_IDENTITY);
        verify(mockDelegate).removeIntegration(TEST_IDENTITY);
    }

    @Test
    public void testIsModbusOpen() {
        when(mockDelegate.isModbusOpen()).thenReturn(true);
        
        boolean result = deviceSpecificSource.isModbusOpen();
        
        assertTrue(result);
        verify(mockDelegate).isModbusOpen();
    }

    @Test
    public void testCloseModbus() {
        deviceSpecificSource.closeModbus();
        // 验证没有调用delegate的closeModbus，因为这是共享资源
        verify(mockDelegate, never()).closeModbus();
    }

    @Test
    public void testGetMaxWaiters() {
        int expectedMaxWaiters = 5;
        when(mockDelegate.getMaxWaiters()).thenReturn(expectedMaxWaiters);
        
        int result = deviceSpecificSource.getMaxWaiters();
        
        assertEquals(expectedMaxWaiters, result);
        verify(mockDelegate, times(2)).getMaxWaiters(); // 一次在构造函数中，一次在测试中
    }

    @Test
    public void testGetWaitTimeoutMs() {
        int expectedWaitTimeoutMs = 3000;
        when(mockDelegate.getWaitTimeoutMs()).thenReturn(expectedWaitTimeoutMs);
        
        int result = deviceSpecificSource.getWaitTimeoutMs();
        
        assertEquals(expectedWaitTimeoutMs, result);
        verify(mockDelegate, times(2)).getWaitTimeoutMs(); // 一次在构造函数中，一次在测试中
    }

    @Test
    public void testGetModbusInfo() {
        when(mockDelegate.getModbusInfo()).thenReturn(mockModbusInfo);
        
        ModbusInfo result = deviceSpecificSource.getModbusInfo();
        
        assertEquals(mockModbusInfo, result);
        verify(mockDelegate, times(2)).getModbusInfo(); // 一次在构造函数中，一次在测试中
    }

    @Test
    public void testDifferentSlaveIds() {
        // 测试不同slaveId的设备使用相同的delegate
        Integer slaveId1 = 1;
        Integer slaveId2 = 2;
        ModbusInfo modbusInfo1 = mock(ModbusInfo.class);
        ModbusInfo modbusInfo2 = mock(ModbusInfo.class);
        when(modbusInfo1.getSlaveId()).thenReturn(slaveId1);
        when(modbusInfo2.getSlaveId()).thenReturn(slaveId2);

        DeviceSpecificModbusSource device1 = new DeviceSpecificModbusSource(mockDelegate, modbusInfo1);
        DeviceSpecificModbusSource device2 = new DeviceSpecificModbusSource(mockDelegate, modbusInfo2);

        assertEquals(slaveId1, device1.getDeviceSlaveId());
        assertEquals(slaveId2, device2.getDeviceSlaveId());

        // 验证它们都委托给同一个delegate
        verify(mockDelegate, never()).readCoilsWithSlaveId(anyInt(), anyInt(), anyInt());

        device1.readCoils(0, 10);
        verify(mockDelegate).readCoilsWithSlaveId(slaveId1, 0, 10);

        device2.readCoils(0, 10);
        verify(mockDelegate).readCoilsWithSlaveId(slaveId2, 0, 10);
    }
}
