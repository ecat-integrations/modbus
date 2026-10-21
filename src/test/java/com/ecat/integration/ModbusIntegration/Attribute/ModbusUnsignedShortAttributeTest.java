package com.ecat.integration.ModbusIntegration.Attribute;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.ecat.core.Device.DeviceBase;
import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.AttrState;
import com.ecat.core.State.UnitInfo;
import com.ecat.core.State.Unit.CurrentUnit;
import com.ecat.core.State.Unit.TemperatureUnit;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.serotonin.modbus4j.msg.WriteRegisterResponse;

/**
 * 锁定 unsigned U16 的位模式解析、单寄存器写帧、单位换算和 0..65535 边界。
 */
public class ModbusUnsignedShortAttributeTest {

    private static final Integer REGISTER_ADDRESS = 0x10;

    @Mock
    private ModbusSource mockModbusSource;
    @Mock
    private AttributeClass mockAttrClass;
    @Mock
    private UnitInfo mockNativeUnit;
    @Mock
    private UnitInfo mockDisplayUnit;

    @Mock
    private DeviceBase mockDevice;

    private ModbusUnsignedShortAttribute attr;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(mockAttrClass.getDisplayName()).thenReturn("UnsignedShortAttr");
        when(mockDisplayUnit.convertUnit(mockNativeUnit)).thenReturn(1.0);
        when(mockModbusSource.acquire()).thenReturn("testKey");
        WriteRegisterResponse successResponse = successResponse();
        when(mockModbusSource.writeRegister(anyInt(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(successResponse));

        attr = new ModbusUnsignedShortAttribute(
                "id", "UnsignedShortAttr", mockAttrClass, mockNativeUnit, mockDisplayUnit, 3,
                true, true, mockModbusSource, REGISTER_ADDRESS
        );
        when(mockDevice.getId()).thenReturn("testDeviceId");
        when(mockDevice.isReady()).thenReturn(true);
        attr.setDevice(mockDevice);
    }

    @Test
    public void testParseRegisterBitPatternsAsUnsigned() {
        assertTrue(attr.updateValue((short) 0x8000, AttributeStatus.NORMAL));
        assertEquals(Integer.valueOf(32768), valueOf(attr));

        assertTrue(attr.updateValue((short) 0xFFFF));
        assertEquals(Integer.valueOf(65535), valueOf(attr));

        assertTrue(attr.updateValue((short) 0));
        assertEquals(Integer.valueOf(0), valueOf(attr));
    }

    @Test
    public void testWriteMapsValueToSingleRegister() throws Exception {
        assertTrue(attr.setDisplayValue("65535").get());

        verify(mockModbusSource, times(1)).writeRegister(REGISTER_ADDRESS, 65535);
        assertEquals(Integer.valueOf(65535), valueOf(attr));
    }

    @Test
    public void testWriteLowerBoundaryToSingleRegister() throws Exception {
        assertTrue(attr.setDisplayValue("0").get());

        verify(mockModbusSource, times(1)).writeRegister(REGISTER_ADDRESS, 0);
        assertEquals(Integer.valueOf(0), valueOf(attr));
    }

    @Test
    public void testWriteModbusExceptionExplicitlyFails() throws Exception {
        WriteRegisterResponse exceptionResponse = mock(WriteRegisterResponse.class);
        when(exceptionResponse.isException()).thenReturn(true);
        when(exceptionResponse.getExceptionMessage()).thenReturn("Test Exception");
        when(mockModbusSource.writeRegister(anyInt(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(exceptionResponse));

        CompletableFuture<Boolean> future = attr.setDisplayValue("123");
        try {
            future.get();
            fail("Modbus 异常必须显式失败");
        } catch (Exception e) {
            assertEquals("命令下发失败: Test Exception", e.getCause().getMessage());
        }
        assertNull(valueOf(attr));
    }

    @Test
    public void testDisplayUnitConversion() {
        when(mockNativeUnit.convertUnit(mockDisplayUnit)).thenReturn(0.001);
        assertTrue(attr.updateValue((short) 65535));

        assertEquals("65.535", attr.getDisplayValue(mockDisplayUnit));
    }

    @Test
    public void testWriteDisplayUnitConversion() throws Exception {
        when(mockDisplayUnit.convertUnit(mockNativeUnit)).thenReturn(1000.0);

        assertTrue(attr.setDisplayValue("65", mockDisplayUnit).get());

        verify(mockModbusSource, times(1)).writeRegister(REGISTER_ADDRESS, 65000);
        assertEquals(Integer.valueOf(65000), valueOf(attr));
    }

    @Test
    public void testUpperBoundaryAcceptedAndLowerNeighborRejected() {
        attr.updateValue(65535);
        assertEquals(Integer.valueOf(65535), valueOf(attr));

        try {
            attr.updateValue(65536);
            fail("65536 超出 unsigned U16，必须显式失败");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("0..65535"));
        }
        assertEquals(Integer.valueOf(65535), valueOf(attr));
    }

    @Test
    public void testNegativeBoundaryRejectedWithoutWrite() throws Exception {
        CompletableFuture<Boolean> future = attr.setDisplayValue("-1");
        try {
            future.get();
            fail("-1 超出 unsigned U16，必须显式失败");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
            assertTrue(e.getCause().getMessage().contains("0..65535"));
        }

        verify(mockModbusSource, never()).writeRegister(anyInt(), anyInt());
        assertNull(valueOf(attr));
    }

    @Test
    public void testUnitConvertedBoundaryIsCheckedAfterConversion() throws Exception {
        when(mockDisplayUnit.convertUnit(mockNativeUnit)).thenReturn(1000.0);

        CompletableFuture<Boolean> future = attr.setDisplayValue("66", mockDisplayUnit);
        try {
            future.get();
            fail("换算后 66000 超出 unsigned U16，必须显式失败");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
            assertTrue(e.getCause().getMessage().contains("0..65535"));
        }

        verify(mockModbusSource, never()).writeRegister(anyInt(), anyInt());
        assertNull(valueOf(attr));
    }

    @Test
    public void testWriteWithIncompatibleUnitFamilyFailsExplicitly() throws Exception {
        ModbusUnsignedShortAttribute currentAttr = new ModbusUnsignedShortAttribute(
                "current", "CurrentAttr", mockAttrClass,
                CurrentUnit.MILLIAMPERE, CurrentUnit.MILLIAMPERE, 0,
                true, true, mockModbusSource, REGISTER_ADDRESS
        );

        CompletableFuture<Boolean> future = currentAttr.setDisplayValue("123", TemperatureUnit.CELSIUS);
        try {
            future.get();
            fail("跨单位族不能按原值猜测写入");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
            assertTrue(e.getCause().getMessage().contains("单位族不兼容"));
        }

        verify(mockModbusSource, never()).writeRegister(anyInt(), anyInt());
        assertNull(valueOf(attr));
    }

    private static WriteRegisterResponse successResponse() {
        WriteRegisterResponse response = mock(WriteRegisterResponse.class);
        when(response.isException()).thenReturn(false);
        return response;
    }

    private static Object valueOf(AttributeBase<?> attr) {
        AttrState state = attr.getState();
        return state != null ? state.getValue() : null;
    }
}
