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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.serotonin.modbus4j.msg.WriteRegistersResponse;
import com.ecat.core.EcatCore;
import com.ecat.core.Task.TaskManager;
import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.State.AttributeStatus;

/**
 * ModbusScalableFloatDRAttributeTest class
 * 
 * 测试ModbusScalableFloatDRAttribute类的功能，包括设置值、更新值、获取显示值等。
 * 
 * @author coffee
 */
public class ModbusScalableFloatDRAttributeTest {

    // 包内可见子类用于测试 protected 方法
    static class TestableModbusScalableFloatDRAttribute extends ModbusScalableFloatDRAttribute {
        public TestableModbusScalableFloatDRAttribute(String attributeID, String displayName, AttributeClass attrClass,
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

    private TestableModbusScalableFloatDRAttribute attr;

    private float scaleFactor = 10.0f; // 缩放因子

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(mockAttrClass.getDisplayName()).thenReturn("ScalableFloatDRAttr");

        // 深度依赖 mock
        when(mockModbusSource.acquire()).thenReturn("testKey");
        when(mockModbusIntegration.register(any(), any())).thenReturn(mockModbusSource);

        WriteRegistersResponse mockWriteResp = mock(WriteRegistersResponse.class);
        when(mockWriteResp.isException()).thenReturn(false);
        when(mockModbusSource.writeRegisters(anyInt(), any())).thenReturn(
            CompletableFuture.completedFuture(mockWriteResp)
        );
        mockTaskManager = mock(TaskManager.class);
        when(mockEcatCore.getTaskManager()).thenReturn(mockTaskManager);
        mockBusRegistry = mock(BusRegistry.class);
        doNothing().when(mockBusRegistry).publish(any(), any());
        when(mockEcatCore.getBusRegistry()).thenReturn(mockBusRegistry);

        attr = new TestableModbusScalableFloatDRAttribute(
                "id", "ScalableFloatDRAttr", mockAttrClass, mockNativeUnit, mockDisplayUnit, 2,
                true, true, mockModbusSource, (short) 0x10, mockConverter, scaleFactor
        );

    }
    @Test
    public void testRegisterAddress(){
        assertNotNull(attr.getRegisterAddress());
        Short test = 0x10;
        assertEquals(test.intValue(), attr.getRegisterAddress().intValue());
    }

    @Test
    public void testSetValue_Success() throws Exception {
        // Test setting a scaled float value successfully, verifying the value is converted using the scale factor via mockConverter, and checking that the conversion method is called as expected.
        int testValue = 1234;
        float scaledValue = testValue / scaleFactor;
        short[] shorts = new short[]{1, 2};
        WriteRegistersResponse mockResponse = mock(WriteRegistersResponse.class);
        when(mockConverter.intToShorts(testValue)).thenReturn(shorts);
        when(mockModbusSource.writeRegisters(anyInt(), eq(shorts))).thenReturn(CompletableFuture.completedFuture(mockResponse));
        when(mockResponse.isException()).thenReturn(false);

        CompletableFuture<Boolean> future = attr.setValue(scaledValue);
        assertTrue(future.get());
        assertEquals(Float.valueOf(scaledValue), attr.getValue());

        verify(mockConverter, times(1)).intToShorts(testValue);
    }

    @Test
    public void testSetValue_NotChangeable() throws Exception {
        // Test that setting value fails when the attribute is not changeable.
        ModbusScalableFloatDRAttribute attr2 = new ModbusScalableFloatDRAttribute(
                "id", "ScalableFloatDRAttr", mockAttrClass, mockNativeUnit, mockDisplayUnit, 2,
                true, false, mockModbusSource, (short) 0x10, mockConverter, scaleFactor
        );
        CompletableFuture<Boolean> future = attr2.setValue(56.78f);
        assertFalse(future.get());
    }

    @Test
    public void testSetValue_ModbusException() {
        // Test handling of Modbus exception when setting value, including scaling logic and mockConverter invocation.
        int testValue = 1234;
        // float scaledValue = testValue / scaleFactor;
        short[] shorts = new short[]{1, 2};
        WriteRegistersResponse mockResponse = mock(WriteRegistersResponse.class);
        when(mockResponse.isException()).thenReturn(true);
        when(mockResponse.getExceptionMessage()).thenReturn("Test Exception");
        when(mockModbusSource.writeRegisters(anyInt(), eq(shorts))).thenReturn(CompletableFuture.completedFuture(mockResponse));

        when(mockConverter.intToShorts((int)(testValue * scaleFactor))).thenReturn(shorts);

        CompletableFuture<Boolean> future = attr.setValue(new Float(testValue));
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
        // Test updating value from Modbus register words, verifying the scaling logic by mocking data conversion and checking mockConverter is called with correct parameters.
        short word1 = 1, word2 = 2;
        float rawValue = 99.0f;
        when(mockConverter.shortsToInt(word1, word2)).thenReturn((int)rawValue);
        // updateValue 应该自动除以 scale
        assertTrue(attr.updateValue(word1, word2));
        assertEquals(Float.valueOf(rawValue / scaleFactor), attr.getValue());
        assertTrue(attr.updateValue(word1, word2,AttributeStatus.NORMAL));
        assertEquals(Float.valueOf(rawValue / scaleFactor), attr.getValue());
        assertEquals(AttributeStatus.NORMAL, attr.getStatus());
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
