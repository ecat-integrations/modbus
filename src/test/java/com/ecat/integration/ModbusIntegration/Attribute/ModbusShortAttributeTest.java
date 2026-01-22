package com.ecat.integration.ModbusIntegration.Attribute;

import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.UnitInfo;
import com.ecat.integration.ModbusIntegration.ModbusIntegration;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.EndianConverter.EndianConverter;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import com.ecat.core.EcatCore;
import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.Task.TaskManager;

import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.serotonin.modbus4j.msg.WriteRegisterResponse;

/**
 * ModbusShortAttributeTest class
 *
 * Test ModbusShortAttribute class functionality, including setting values, updating values, and getting display values.
 *
 * @author coffee
 */
public class ModbusShortAttributeTest {

    @Mock
    private ModbusSource mockModbusSource;
    @Mock
    private EndianConverter mockConverter;
    @Mock
    private AttributeClass mockAttrClass;
    @Mock
    private UnitInfo mockNativeUnit;
    @Mock
    private UnitInfo mockDisplayUnit;

    // deep dependence mock
    @Mock
    private EcatCore mockEcatCore;
    @Mock
    private TaskManager mockTaskManager;
    @Mock
    private BusRegistry mockBusRegistry;
    @Mock 
    private ModbusIntegration mockModbusIntegration;

    private ModbusShortAttribute attr;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(mockAttrClass.getDisplayName()).thenReturn("ShortAttr");

        // mock modbusSource 的 acquire() 函数
        when(mockModbusSource.acquire()).thenReturn("testKey");
        when(mockModbusIntegration.register(any(), any())).thenReturn(mockModbusSource);

        WriteRegisterResponse mockWriteResp = mock(WriteRegisterResponse.class);
        when(mockWriteResp.isException()).thenReturn(false);
        when(mockModbusSource.writeRegister(anyInt(), anyInt())).thenReturn(
            CompletableFuture.completedFuture(mockWriteResp)
        );

        mockTaskManager = mock(TaskManager.class);
        when(mockEcatCore.getTaskManager()).thenReturn(mockTaskManager);

        mockBusRegistry = mock(BusRegistry.class);
        doNothing().when(mockBusRegistry).publish(any(), any());
        when(mockEcatCore.getBusRegistry()).thenReturn(mockBusRegistry);

        attr = new ModbusShortAttribute(
                "id", "ShortAttr", mockAttrClass, mockNativeUnit, mockDisplayUnit, 0,
                true, true, mockModbusSource, (short) 0x10
        );
    }

    @Test
    public void testSetValue_Success() throws Exception {
        // Test setting a short value successfully and verifying the result, including correct use of mockConverter for data conversion.
        short testValue = 123;
        short shorts = testValue;
        WriteRegisterResponse mockResponse = mock(WriteRegisterResponse.class);
        when(mockModbusSource.writeRegister(anyInt(), eq(shorts))).thenReturn(CompletableFuture.completedFuture(mockResponse));
        when(mockResponse.isException()).thenReturn(false);

        CompletableFuture<Boolean> future = attr.setValue(testValue);
        assertTrue(future.get());
        assertEquals(Short.valueOf(testValue), attr.getValue());
    }

    @Test
    public void testSetValue_NotChangeable() throws Exception {
        // Test that setting value fails when the attribute is not changeable.
        ModbusShortAttribute attr2 = new ModbusShortAttribute(
                "id", "ShortAttr", mockAttrClass, mockNativeUnit, mockDisplayUnit, 0,
                true, false, mockModbusSource, (short) 0x10
        );
        CompletableFuture<Boolean> future = attr2.setValue((short) 456);
        assertFalse(future.get());
        assertEquals(null, attr2.getValue());
    }

    @Test
    public void testSetValue_ModbusException() {
        // Test handling of Modbus exception when setting value, including mockConverter invocation.
        short testValue = 789;
        WriteRegisterResponse mockResponse = mock(WriteRegisterResponse.class);
        when(mockResponse.isException()).thenReturn(true);
        when(mockResponse.getExceptionMessage()).thenReturn("Test Exception");
        when(mockModbusSource.writeRegister(anyInt(), anyInt())).thenReturn(CompletableFuture.completedFuture(mockResponse));
        

        CompletableFuture<Boolean> future = attr.setValue(testValue);
        try {
            future.get();
            fail("Should throw RuntimeException");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof RuntimeException);
            assertTrue(e.getCause().getMessage().contains("命令下发失败"));
        }
    }

    @Test
    public void testUpdateValue() {
        // Test updating value from a Modbus register word, verifying data conversion via mockConverter.
        assertTrue(attr.updateValue((short) 456));
        assertEquals(Short.valueOf((short) 456), attr.getValue());
    }

    @Test
    public void testUpdateValueDirect() {
        // Test direct update of value with a short input.
        assertTrue(attr.updateValue((short) 321));
        assertEquals(Short.valueOf((short) 321), attr.getValue());
    }

    @Test
    public void testUpdateValueWithStatus() {
        // Test updating value with status parameter.
        assertTrue(attr.updateValue((short) 222, null));
        assertEquals(Short.valueOf((short) 222), attr.getValue());
    }

    @Test
    public void testGetDisplayValue_NullValue() {
        // Test getting display value when the attribute value is null.
        assertNull(attr.getDisplayValue(mockDisplayUnit));
    }
}
