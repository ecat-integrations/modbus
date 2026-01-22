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

import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.serotonin.modbus4j.msg.WriteRegisterResponse;
import com.ecat.core.EcatCore;
import com.ecat.core.Task.TaskManager;
import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.State.AttributeStatus;

/**
 * ModbusScalableFloatDRAttributeTest class
 * 
 * Test ModbusScalableFloatSRAttribute class functionality, including setting values, updating values, and getting display values.
 * 
 * @author coffee
 */
public class ModbusScalableFloatSRAttributeTest {

    // 包内可见子类用于测试 protected 方法
    static class TestableModbusScalableFloatSRAttribute extends ModbusScalableFloatSRAttribute {
        public TestableModbusScalableFloatSRAttribute(String attributeID, String displayName, AttributeClass attrClass,
                UnitInfo nativeUnit, UnitInfo displayUnit, int displayPrecision, boolean unitChangeable,
                boolean valueChangeable, ModbusSource modbusSource, Short registerAddress,
                EndianConverter endianConverter, float scale) {
            super(attributeID, displayName, attrClass, nativeUnit, displayUnit, displayPrecision, unitChangeable,
                    valueChangeable, modbusSource, registerAddress, endianConverter, scale);
        }
        @Override
        public boolean updateValue(Float value) {
            return super.updateValue(value);
        }
        @Override
        public boolean updateValue(Float value, AttributeStatus status) {
            return super.updateValue(value, status);
        }
    }

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

    // 深层依赖 mock
    @Mock
    private EcatCore mockEcatCore;
    @Mock
    private TaskManager mockTaskManager;
    @Mock
    private BusRegistry mockBusRegistry;

    @Mock 
    private ModbusIntegration mockModbusIntegration;

    private TestableModbusScalableFloatSRAttribute attr;

    private float scaleFactor = 10.0f; // 缩放因子

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(mockAttrClass.getDisplayName()).thenReturn("ScalableFloatSRAttr");

        // 深度依赖 mock
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

        attr = new TestableModbusScalableFloatSRAttribute(
                "id", "ScalableFloatSRAttr", mockAttrClass, mockNativeUnit, mockDisplayUnit, 2,
                true, true, mockModbusSource, (short) 0x10, mockConverter, scaleFactor
        );
    }

    @Test
    public void testSetValue_Success() throws Exception {
        // Test setting a scaled float value successfully, verifying the value is converted using the scale factor via mockConverter, and checking that the conversion method is called as expected.
        float testValue = 12.34f;
        float scaledValue = testValue * scaleFactor;
        short shortValue = (short)(scaledValue / scaleFactor);
        WriteRegisterResponse mockResponse = mock(WriteRegisterResponse.class);
        when(mockConverter.intToShort((int)scaledValue)).thenReturn(shortValue);
        when(mockModbusSource.writeRegister(anyShort(), eq((int)shortValue))).thenReturn(CompletableFuture.completedFuture(mockResponse));
        when(mockResponse.isException()).thenReturn(false);

        CompletableFuture<Boolean> future = attr.setValue(testValue);
        assertTrue(future.get());
        assertEquals(Float.valueOf(testValue), attr.getValue());

        // 判断mockConverter.intToShort((int)scaledValue) 被执行过一次，确认缩放比例正确
        verify(mockConverter, times(1)).intToShort((int)scaledValue);
    }

    @Test
    public void testSetValue_NotChangeable() throws Exception {
        // Test that setting value fails when the attribute is not changeable.
        ModbusScalableFloatSRAttribute attr2 = new ModbusScalableFloatSRAttribute(
                "id", "ScalableFloatSRAttr", mockAttrClass, mockNativeUnit, mockDisplayUnit, 2,
                true, false, mockModbusSource, (short) 0x10, mockConverter, scaleFactor
        );
        CompletableFuture<Boolean> future = attr2.setValue(56.78f);
        assertFalse(future.get());
    }

    @Test
    public void testSetValue_ModbusException() {
        // Test handling of Modbus exception when setting value, including scaling logic and mockConverter invocation.
        float testValue = 12.34f;
        float scaledValue = testValue * scaleFactor;
        short shortValue = (short)(scaledValue / scaleFactor);
        WriteRegisterResponse mockResponse = mock(WriteRegisterResponse.class);
        when(mockConverter.intToShort((int)scaledValue)).thenReturn(shortValue);
        when(mockModbusSource.writeRegister(anyInt(), eq((int)shortValue))).thenReturn(CompletableFuture.completedFuture(mockResponse));
        when(mockResponse.isException()).thenReturn(true);
        when(mockResponse.getExceptionMessage()).thenReturn("Test Exception");

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
        // Test updating value from Modbus register word, verifying the scaling logic by mocking data conversion and checking mockConverter is called with correct parameters.
        short word1 = 1;
        short rawValue = 99;
        when(mockConverter.shortToInt(word1)).thenReturn((int)rawValue);
        // updateValue 应该自动除以 scale
        assertTrue(attr.updateValue(word1));
        assertEquals(Float.valueOf(rawValue / scaleFactor), attr.getValue());
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
