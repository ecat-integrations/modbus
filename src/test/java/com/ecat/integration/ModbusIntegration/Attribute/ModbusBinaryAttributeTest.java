package com.ecat.integration.ModbusIntegration.Attribute;

import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttrState;
import com.ecat.integration.ModbusIntegration.ModbusIntegration;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import com.ecat.core.EcatCore;
import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.Bus.event.BusEvent;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Task.TaskManager;

import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.serotonin.modbus4j.msg.WriteCoilResponse;

/**
 * ModbusBinaryAttributeTest class
 *
 * Test ModbusBinaryAttribute class functionality, including setting values, updating values, and getting display values.
 *
 * @author coffee
 */
public class ModbusBinaryAttributeTest {

    @Mock
    private ModbusSource mockModbusSource;
    @Mock
    private AttributeClass mockAttrClass;

    // deep dependence mock
    @Mock
    private EcatCore mockEcatCore;
    @Mock
    private TaskManager mockTaskManager;
    @Mock
    private BusRegistry mockBusRegistry;
    @Mock
    private ModbusIntegration mockModbusIntegration;
    @Mock
    private DeviceBase mockDevice;

    private ModbusBinaryAttribute attr;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(mockAttrClass.getDisplayName()).thenReturn("BinaryAttr");

        // mock modbusSource 的 acquire() 函数
        when(mockModbusSource.acquire()).thenReturn("testKey");
        when(mockModbusIntegration.register(any(), any())).thenReturn(mockModbusSource);

        WriteCoilResponse mockWriteResp = mock(WriteCoilResponse.class);
        when(mockWriteResp.isException()).thenReturn(false);
        when(mockModbusSource.writeCoil(anyInt(), anyBoolean())).thenReturn(
            CompletableFuture.completedFuture(mockWriteResp)
        );

        mockTaskManager = mock(TaskManager.class);
        when(mockEcatCore.getTaskManager()).thenReturn(mockTaskManager);

        mockBusRegistry = mock(BusRegistry.class);
        doNothing().when(mockBusRegistry).publish(any(BusEvent.class));
        when(mockEcatCore.getBusRegistry()).thenReturn(mockBusRegistry);

        when(mockDevice.getId()).thenReturn("mockDeviceId");
        when(mockDevice.isReady()).thenReturn(true);   // 硬门禁：READY 才允许 publish

        attr = new ModbusBinaryAttribute(
                "id", "BinaryAttr", mockAttrClass, true, mockModbusSource, 1
        );

        attr.setDevice(mockDevice);
    }

    @Test
    public void testAsyncTurnOn_Success() throws Exception {
        // Test turning on the relay successfully
        WriteCoilResponse mockResponse = mock(WriteCoilResponse.class);
        when(mockModbusSource.writeCoil(eq((int) 0x01), eq(true))).thenReturn(CompletableFuture.completedFuture(mockResponse));
        when(mockResponse.isException()).thenReturn(false);

        CompletableFuture<Boolean> future = attr.asyncTurnOn();
        assertTrue(future.get());
        assertEquals(Boolean.TRUE, valueOf(attr));
    }

    @Test
    public void testAsyncTurnOff_Success() throws Exception {
        // Test turning off the relay successfully
        WriteCoilResponse mockResponse = mock(WriteCoilResponse.class);
        when(mockModbusSource.writeCoil(eq((int) 0x01), eq(false))).thenReturn(CompletableFuture.completedFuture(mockResponse));
        when(mockResponse.isException()).thenReturn(false);

        CompletableFuture<Boolean> future = attr.asyncTurnOff();
        assertTrue(future.get());
        assertEquals(Boolean.FALSE, valueOf(attr));
    }

    @Test
    public void testAsyncTurnOn_NotChangeable() throws Exception {
        // Test that turning on fails when the attribute is not changeable.
        ModbusBinaryAttribute attr2 = new ModbusBinaryAttribute(
                "id", "BinaryAttr", mockAttrClass, false, mockModbusSource, (int) 0x02
        );
        // 必须 mock 设备：asyncTurnOn 的 exceptionally 收尾路径会 getDevice().getId() 记日志，
        // 缺设备时该收尾自身 NPE 掩盖真实的 false 结果
        attr2.setDevice(mockDevice);
        CompletableFuture<Boolean> future = attr2.asyncTurnOn();
        assertFalse(future.get());
        assertEquals(null, valueOf(attr2));
    }

    @Test
    public void testAsyncTurnOn_ModbusException() {
        // Test handling of Modbus exception when turning on
        WriteCoilResponse mockResponse = mock(WriteCoilResponse.class);
        when(mockResponse.isException()).thenReturn(true);
        when(mockResponse.getExceptionMessage()).thenReturn("Test Exception");
        when(mockModbusSource.writeCoil(eq((int) 0x01), eq(true))).thenReturn(CompletableFuture.completedFuture(mockResponse));

        CompletableFuture<Boolean> future = attr.asyncTurnOn();
        try {
            Boolean result = future.get();
            assertFalse(result);
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof RuntimeException);
            assertTrue(e.getCause().getMessage().contains("命令下发失败"));
        }
    }

    @Test
    public void testUpdateValue() {
        // Test updating value from a Modbus coil state
        assertTrue(attr.updateValue(true));
        assertEquals(Boolean.valueOf(true), valueOf(attr));

        assertTrue(attr.updateValue(false));
        assertEquals(Boolean.valueOf(false), valueOf(attr));
    }

    @Test
    public void testUpdateValueDirect() {
        // Test direct update of value with a Boolean input
        assertTrue(attr.updateValue(Boolean.TRUE));
        assertEquals(Boolean.valueOf(true), valueOf(attr));

        assertTrue(attr.updateValue(Boolean.FALSE));
        assertEquals(Boolean.valueOf(false), valueOf(attr));
    }

    
    @Test
    public void testGetDisplayValue_NullValue() {
        // Test getting display value when the attribute value is null
        assertNull(attr.getDisplayValue(null));
    }

    // 通过不可变 AttrState 读取属性值（getValue() 已封装为 protected）
    private static Object valueOf(AttributeBase<?> attr) {
        AttrState s = attr.getState();
        return s != null ? s.getValue() : null;
    }
}
