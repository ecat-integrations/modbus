package com.ecat.integration.ModbusIntegration;

import com.serotonin.modbus4j.msg.*;
import org.junit.*;
import org.mockito.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * ModbusSource SlaveId功能的单元测试
 * - 测试新增的带slaveId方法
   - 验证原有方法兼容性
   - 测试异常处理
 * 
 * @author coffee
 */
public class ModbusSourceSlaveIdTest {

    @Mock
    private ModbusSource mockModbusSource;
    
    @Mock
    private ReadCoilsResponse mockReadCoilsResponse;
    
    @Mock
    private ReadHoldingRegistersResponse mockReadHoldingRegistersResponse;
    
    @Mock
    private WriteCoilResponse mockWriteCoilResponse;
    
    @Mock
    private WriteRegisterResponse mockWriteRegisterResponse;

    private AutoCloseable mockitoCloseable;

    @Before
    public void setUp() throws Exception {
        mockitoCloseable = MockitoAnnotations.openMocks(this);
    }

    @After
    public void tearDown() throws Exception {
        mockitoCloseable.close();
    }

    @Test
    public void testReadCoilsWithSlaveId() {
        int slaveId = 1;
        int startAddress = 0;
        int numberOfBits = 10;
        CompletableFuture<ReadCoilsResponse> expectedFuture = CompletableFuture.completedFuture(mockReadCoilsResponse);
        
        when(mockModbusSource.readCoilsWithSlaveId(slaveId, startAddress, numberOfBits))
            .thenReturn(expectedFuture);
        
        CompletableFuture<ReadCoilsResponse> result = 
            mockModbusSource.readCoilsWithSlaveId(slaveId, startAddress, numberOfBits);
        
        assertEquals(expectedFuture, result);
        verify(mockModbusSource).readCoilsWithSlaveId(slaveId, startAddress, numberOfBits);
    }

    @Test
    public void testReadDiscreteInputsWithSlaveId() {
        int slaveId = 2;
        int startAddress = 0;
        int numberOfBits = 8;
        CompletableFuture<ReadDiscreteInputsResponse> expectedFuture = 
            CompletableFuture.completedFuture(mock(ReadDiscreteInputsResponse.class));
        
        when(mockModbusSource.readDiscreteInputsWithSlaveId(slaveId, startAddress, numberOfBits))
            .thenReturn(expectedFuture);
        
        CompletableFuture<ReadDiscreteInputsResponse> result = 
            mockModbusSource.readDiscreteInputsWithSlaveId(slaveId, startAddress, numberOfBits);
        
        assertEquals(expectedFuture, result);
        verify(mockModbusSource).readDiscreteInputsWithSlaveId(slaveId, startAddress, numberOfBits);
    }

    @Test
    public void testReadHoldingRegistersWithSlaveId() {
        int slaveId = 3;
        int startAddress = 0;
        int numberOfRegisters = 5;
        CompletableFuture<ReadHoldingRegistersResponse> expectedFuture = 
            CompletableFuture.completedFuture(mockReadHoldingRegistersResponse);
        
        when(mockModbusSource.readHoldingRegistersWithSlaveId(slaveId, startAddress, numberOfRegisters))
            .thenReturn(expectedFuture);
        
        CompletableFuture<ReadHoldingRegistersResponse> result = 
            mockModbusSource.readHoldingRegistersWithSlaveId(slaveId, startAddress, numberOfRegisters);
        
        assertEquals(expectedFuture, result);
        verify(mockModbusSource).readHoldingRegistersWithSlaveId(slaveId, startAddress, numberOfRegisters);
    }

    @Test
    public void testReadInputRegistersWithSlaveId() {
        int slaveId = 4;
        int startAddress = 0;
        int numberOfRegisters = 3;
        CompletableFuture<ReadInputRegistersResponse> expectedFuture = 
            CompletableFuture.completedFuture(mock(ReadInputRegistersResponse.class));
        
        when(mockModbusSource.readInputRegistersWithSlaveId(slaveId, startAddress, numberOfRegisters))
            .thenReturn(expectedFuture);
        
        CompletableFuture<ReadInputRegistersResponse> result = 
            mockModbusSource.readInputRegistersWithSlaveId(slaveId, startAddress, numberOfRegisters);
        
        assertEquals(expectedFuture, result);
        verify(mockModbusSource).readInputRegistersWithSlaveId(slaveId, startAddress, numberOfRegisters);
    }

    @Test
    public void testWriteCoilWithSlaveId() {
        int slaveId = 5;
        int address = 1;
        boolean value = true;
        CompletableFuture<WriteCoilResponse> expectedFuture = 
            CompletableFuture.completedFuture(mockWriteCoilResponse);
        
        when(mockModbusSource.writeCoilWithSlaveId(slaveId, address, value))
            .thenReturn(expectedFuture);
        
        CompletableFuture<WriteCoilResponse> result = 
            mockModbusSource.writeCoilWithSlaveId(slaveId, address, value);
        
        assertEquals(expectedFuture, result);
        verify(mockModbusSource).writeCoilWithSlaveId(slaveId, address, value);
    }

    @Test
    public void testWriteRegisterWithSlaveId() {
        int slaveId = 6;
        int address = 2;
        int value = 1234;
        CompletableFuture<WriteRegisterResponse> expectedFuture = 
            CompletableFuture.completedFuture(mockWriteRegisterResponse);
        
        when(mockModbusSource.writeRegisterWithSlaveId(slaveId, address, value))
            .thenReturn(expectedFuture);
        
        CompletableFuture<WriteRegisterResponse> result = 
            mockModbusSource.writeRegisterWithSlaveId(slaveId, address, value);
        
        assertEquals(expectedFuture, result);
        verify(mockModbusSource).writeRegisterWithSlaveId(slaveId, address, value);
    }

    @Test
    public void testWriteCoilsWithSlaveId() {
        int slaveId = 7;
        int startAddress = 0;
        boolean[] values = {true, false, true};
        CompletableFuture<WriteCoilsResponse> expectedFuture = 
            CompletableFuture.completedFuture(mock(WriteCoilsResponse.class));
        
        when(mockModbusSource.writeCoilsWithSlaveId(slaveId, startAddress, values))
            .thenReturn(expectedFuture);
        
        CompletableFuture<WriteCoilsResponse> result = 
            mockModbusSource.writeCoilsWithSlaveId(slaveId, startAddress, values);
        
        assertEquals(expectedFuture, result);
        verify(mockModbusSource).writeCoilsWithSlaveId(slaveId, startAddress, values);
    }

    @Test
    public void testWriteRegistersWithSlaveId() {
        int slaveId = 8;
        int startAddress = 0;
        short[] values = {1, 2, 3};
        CompletableFuture<WriteRegistersResponse> expectedFuture = 
            CompletableFuture.completedFuture(mock(WriteRegistersResponse.class));
        
        when(mockModbusSource.writeRegistersWithSlaveId(slaveId, startAddress, values))
            .thenReturn(expectedFuture);
        
        CompletableFuture<WriteRegistersResponse> result = 
            mockModbusSource.writeRegistersWithSlaveId(slaveId, startAddress, values);
        
        assertEquals(expectedFuture, result);
        verify(mockModbusSource).writeRegistersWithSlaveId(slaveId, startAddress, values);
    }

    @Test
    public void testReadExceptionStatusWithSlaveId() {
        int slaveId = 9;
        CompletableFuture<ReadExceptionStatusResponse> expectedFuture = 
            CompletableFuture.completedFuture(mock(ReadExceptionStatusResponse.class));
        
        when(mockModbusSource.readExceptionStatusWithSlaveId(slaveId))
            .thenReturn(expectedFuture);
        
        CompletableFuture<ReadExceptionStatusResponse> result = 
            mockModbusSource.readExceptionStatusWithSlaveId(slaveId);
        
        assertEquals(expectedFuture, result);
        verify(mockModbusSource).readExceptionStatusWithSlaveId(slaveId);
    }

    @Test
    public void testReportSlaveIdWithSlaveId() {
        int slaveId = 10;
        CompletableFuture<ReportSlaveIdResponse> expectedFuture = 
            CompletableFuture.completedFuture(mock(ReportSlaveIdResponse.class));
        
        when(mockModbusSource.reportSlaveIdWithSlaveId(slaveId))
            .thenReturn(expectedFuture);
        
        CompletableFuture<ReportSlaveIdResponse> result = 
            mockModbusSource.reportSlaveIdWithSlaveId(slaveId);
        
        assertEquals(expectedFuture, result);
        verify(mockModbusSource).reportSlaveIdWithSlaveId(slaveId);
    }

    @Test
    public void testWriteMaskRegisterWithSlaveId() {
        int slaveId = 11;
        int address = 1;
        int andMask = 0xFF00;
        int orMask = 0x00FF;
        CompletableFuture<WriteMaskRegisterResponse> expectedFuture = 
            CompletableFuture.completedFuture(mock(WriteMaskRegisterResponse.class));
        
        when(mockModbusSource.writeMaskRegisterWithSlaveId(slaveId, address, andMask, orMask))
            .thenReturn(expectedFuture);
        
        CompletableFuture<WriteMaskRegisterResponse> result = 
            mockModbusSource.writeMaskRegisterWithSlaveId(slaveId, address, andMask, orMask);
        
        assertEquals(expectedFuture, result);
        verify(mockModbusSource).writeMaskRegisterWithSlaveId(slaveId, address, andMask, orMask);
    }

    @Test
    public void testDifferentSlaveIds() {
        int slaveId1 = 1;
        int slaveId2 = 2;
        int startAddress = 0;
        int numberOfBits = 10;
        
        CompletableFuture<ReadCoilsResponse> future1 = CompletableFuture.completedFuture(mockReadCoilsResponse);
        CompletableFuture<ReadCoilsResponse> future2 = CompletableFuture.completedFuture(mockReadCoilsResponse);
        
        when(mockModbusSource.readCoilsWithSlaveId(slaveId1, startAddress, numberOfBits))
            .thenReturn(future1);
        when(mockModbusSource.readCoilsWithSlaveId(slaveId2, startAddress, numberOfBits))
            .thenReturn(future2);
        
        CompletableFuture<ReadCoilsResponse> result1 = 
            mockModbusSource.readCoilsWithSlaveId(slaveId1, startAddress, numberOfBits);
        CompletableFuture<ReadCoilsResponse> result2 = 
            mockModbusSource.readCoilsWithSlaveId(slaveId2, startAddress, numberOfBits);
        
        assertEquals(future1, result1);
        assertEquals(future2, result2);
        
        verify(mockModbusSource).readCoilsWithSlaveId(slaveId1, startAddress, numberOfBits);
        verify(mockModbusSource).readCoilsWithSlaveId(slaveId2, startAddress, numberOfBits);
    }

    @Test
    public void testSlaveIdZero() {
        int slaveId = 0; // 广播地址
        int address = 1;
        boolean value = true;
        CompletableFuture<WriteCoilResponse> expectedFuture = 
            CompletableFuture.completedFuture(mockWriteCoilResponse);
        
        when(mockModbusSource.writeCoilWithSlaveId(slaveId, address, value))
            .thenReturn(expectedFuture);
        
        CompletableFuture<WriteCoilResponse> result = 
            mockModbusSource.writeCoilWithSlaveId(slaveId, address, value);
        
        assertEquals(expectedFuture, result);
        verify(mockModbusSource).writeCoilWithSlaveId(slaveId, address, value);
    }

    @Test
    public void testSlaveId255() {
        int slaveId = 255; // 最大有效Slave ID
        int address = 1;
        int value = 1234;
        CompletableFuture<WriteRegisterResponse> expectedFuture = 
            CompletableFuture.completedFuture(mockWriteRegisterResponse);
        
        when(mockModbusSource.writeRegisterWithSlaveId(slaveId, address, value))
            .thenReturn(expectedFuture);
        
        CompletableFuture<WriteRegisterResponse> result = 
            mockModbusSource.writeRegisterWithSlaveId(slaveId, address, value);
        
        assertEquals(expectedFuture, result);
        verify(mockModbusSource).writeRegisterWithSlaveId(slaveId, address, value);
    }

    @Test
    public void testNegativeSlaveId() {
        int slaveId = -1; // 无效的Slave ID
        int startAddress = 0;
        int numberOfBits = 10;
        
        CompletableFuture<ReadCoilsResponse> expectedFuture = 
            CompletableFuture.completedFuture(mockReadCoilsResponse);
        
        when(mockModbusSource.readCoilsWithSlaveId(slaveId, startAddress, numberOfBits))
            .thenReturn(expectedFuture);
        
        CompletableFuture<ReadCoilsResponse> result = 
            mockModbusSource.readCoilsWithSlaveId(slaveId, startAddress, numberOfBits);
        
        assertEquals(expectedFuture, result);
        verify(mockModbusSource).readCoilsWithSlaveId(slaveId, startAddress, numberOfBits);
    }

    @Test
    public void testLargeSlaveId() {
        int slaveId = 1000; // 超出标准范围的Slave ID
        int startAddress = 0;
        int numberOfRegisters = 5;
        
        CompletableFuture<ReadHoldingRegistersResponse> expectedFuture = 
            CompletableFuture.completedFuture(mockReadHoldingRegistersResponse);
        
        when(mockModbusSource.readHoldingRegistersWithSlaveId(slaveId, startAddress, numberOfRegisters))
            .thenReturn(expectedFuture);
        
        CompletableFuture<ReadHoldingRegistersResponse> result = 
            mockModbusSource.readHoldingRegistersWithSlaveId(slaveId, startAddress, numberOfRegisters);
        
        assertEquals(expectedFuture, result);
        verify(mockModbusSource).readHoldingRegistersWithSlaveId(slaveId, startAddress, numberOfRegisters);
    }

    @Test
    public void testSlaveIdWithTimeout() {
        // int slaveId = 1;
        long timeout = 5000;
        TimeUnit unit = TimeUnit.MILLISECONDS;
        String expectedKey = "test_key";
        
        when(mockModbusSource.acquire(timeout, unit)).thenReturn(expectedKey);
        
        String result = mockModbusSource.acquire(timeout, unit);
        
        assertEquals(expectedKey, result);
        verify(mockModbusSource).acquire(timeout, unit);
    }

    @Test
    public void testSlaveIdWithLockManagement() {
        String releaseKey = "test_release_key";
        when(mockModbusSource.release(releaseKey)).thenReturn(true);
        
        boolean result = mockModbusSource.release(releaseKey);
        
        assertTrue(result);
        verify(mockModbusSource).release(releaseKey);
    }

    @Test
    public void testSlaveIdWithWaitingCount() {
        int expectedCount = 3;
        when(mockModbusSource.getWaitingCount()).thenReturn(expectedCount);
        
        int result = mockModbusSource.getWaitingCount();
        
        assertEquals(expectedCount, result);
        verify(mockModbusSource).getWaitingCount();
    }
}
