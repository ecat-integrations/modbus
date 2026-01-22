package com.ecat.integration.ModbusIntegration.Attribute;

import com.ecat.core.EcatCore;
import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.UnitInfo;
import com.ecat.core.Task.TaskManager;
import com.ecat.integration.ModbusIntegration.ModbusIntegration;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.EndianConverter.EndianConverter;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.serotonin.modbus4j.msg.WriteRegistersResponse;

/**
 * ModbusFloatAttributeTest class
 * 
 * 测试ModbusFloatAttribute类的功能，包括设置值、更新值、获取显示值等。
 * 
 * @author coffee
 */
public class ModbusFloatAttributeTest {

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
    @Mock 
    private ModbusIntegration mockModbusIntegration;
    @Mock 
    private EcatCore mockEcatCore;
    @Mock 
    private BusRegistry mockBusRegistry;

    private ModbusFloatAttribute attr;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(mockAttrClass.getDisplayName()).thenReturn("FloatAttr");
        attr = new ModbusFloatAttribute(
                "id", "名称", mockAttrClass, mockNativeUnit, mockDisplayUnit, 2,
                true, true, mockModbusSource, (short) 0x10, mockConverter
        );

        // mock modbusSource 的 acquire() 函数
        when(mockModbusSource.acquire()).thenReturn("testKey");
        when(mockModbusIntegration.register(any(), any())).thenReturn(mockModbusSource);

        com.serotonin.modbus4j.msg.WriteRegistersResponse mockWriteResp = mock(WriteRegistersResponse.class);
        when(mockWriteResp.isException()).thenReturn(false);
        when(mockModbusSource.writeRegisters(anyInt(), any())).thenReturn(
            CompletableFuture.completedFuture(mockWriteResp)
        );

        TaskManager mockTaskManager = mock(TaskManager.class);
        when(mockEcatCore.getTaskManager()).thenReturn(mockTaskManager);
        // when(mockTaskManager.getExecutorService()).thenReturn(mockExecutor);

        mockBusRegistry = mock(BusRegistry.class);
        doNothing().when(mockBusRegistry).publish(any(), any());
        when(mockEcatCore.getBusRegistry()).thenReturn(mockBusRegistry);

    }

    @Test
    public void testSetValue_Success() throws Exception {
        // Test setting a float value successfully and verifying the result, including correct use of mockConverter for data conversion.
        float testValue = 12.34f;
        short[] shorts = new short[]{1, 2};
        WriteRegistersResponse mockResponse = mock(WriteRegistersResponse.class);
        when(mockConverter.floatToShorts(testValue)).thenReturn(shorts);
        when(mockModbusSource.writeRegisters(anyShort(), eq(shorts))).thenReturn(CompletableFuture.completedFuture(mockResponse));
        when(mockResponse.isException()).thenReturn(false);

        CompletableFuture<Boolean> future = attr.setValue(testValue);
        assertTrue(future.get());
        assertEquals(Float.valueOf(testValue), attr.getValue(), 0.001f);
    }

    @Test
    public void testSetValue_NotChangeable() throws Exception {
        // Test that setting value fails when the attribute is not changeable.
        ModbusFloatAttribute attr2 = new ModbusFloatAttribute(
                "id", "名称", mockAttrClass, mockNativeUnit, mockDisplayUnit, 2,
                true, false, mockModbusSource, (short) 0x10, mockConverter
        );
        CompletableFuture<Boolean> future = attr2.setValue(56.78f);
        assertFalse(future.get());
        assertNull(attr2.getValue());
    }

    @Test
    public void testSetValue_ModbusException() {
        // Test handling of Modbus exception when setting value, including mockConverter invocation.
        float testValue = 12.34f;
        short[] shorts = new short[]{1, 2};

        WriteRegistersResponse mockResponse = mock(WriteRegistersResponse.class);
        when(mockResponse.isException()).thenReturn(true);
        when(mockResponse.getExceptionMessage()).thenReturn("Test Exception");
        when(mockModbusSource.writeRegisters(anyInt(), eq(shorts))).thenReturn(CompletableFuture.completedFuture(mockResponse));

        when(mockConverter.floatToShorts(testValue)).thenReturn(shorts);

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
        // Test updating value from Modbus register words, verifying data conversion via mockConverter.
        when(mockConverter.shortsToFloat((short) 1, (short) 2)).thenReturn(99.99f);
        assertTrue(attr.updateValue((short) 1, (short) 2));
        assertEquals(Float.valueOf(99.99f), attr.getValue());
    }

    @Test
    public void testUpdateValueDirect() {
        // Test direct update of value with a float input.
        assertTrue(attr.updateValue(123.45f));
        assertEquals(Float.valueOf(123.45f), attr.getValue());
    }

    @Test
    public void testUpdateValueWithStatus() {
        // Test updating value with status parameter.
        assertTrue(attr.updateValue(88.88f, null));
        assertEquals(Float.valueOf(88.88f), attr.getValue());
    }

    @Test
    public void testGetDisplayValue_NullValue() {
        // Test getting display value when the attribute value is null.
        assertNull(attr.getDisplayValue(mockDisplayUnit));
    }
}
