package com.ecat.integration.ModbusIntegration.Attribute;

import com.ecat.core.Device.DeviceBase;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.UnitInfo;
import com.ecat.core.I18n.I18nKeyPath;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.EndianConverter.EndianConverter;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * ModbusI18nTest class
 *
 * 测试Modbus属性类的国际化功能，包括新的设备分组路径和向后兼容性。
 *
 * @author coffee
 */
public class ModbusI18nTest {

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
    private DeviceBase mockDevice;

    private ModbusFloatAttribute floatAttr;
    private ModbusBinaryAttribute binaryAttr;
    private ModbusShortAttribute shortAttr;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(mockAttrClass.getDisplayName()).thenReturn("TestAttr");

        // 创建测试属性
        floatAttr = new ModbusFloatAttribute(
            "temperature", mockAttrClass, mockNativeUnit, mockDisplayUnit, 2,
            true, true, mockModbusSource, (short) 0x10, mockConverter
        );

        binaryAttr = new ModbusBinaryAttribute(
            "status", mockAttrClass, true, mockModbusSource, 1
        );

        shortAttr = new ModbusShortAttribute(
            "value", mockAttrClass, mockNativeUnit, mockDisplayUnit, 0,
            true, true, mockModbusSource, (short) 0x20
        );
    }

    @Test
    public void testModbusFloatAttributeWithoutDevice() {
        // 测试未绑定设备时的路径前缀（向后兼容）
        I18nKeyPath prefixPath = floatAttr.getI18nPrefixPath();
        assertEquals("state.numeric_attr.", prefixPath.getPathPrefix());
        assertEquals("", prefixPath.getLastSegment());
        assertEquals("state.numeric_attr.", prefixPath.getFullPath());

        // 测试显示名称 - 使用getDisplayName()间接测试路径
        String displayName = floatAttr.getDisplayName();
        assertNotNull(displayName);
    }

    @Test
    public void testModbusBinaryAttributeWithoutDevice() {
        // 测试未绑定设备时的路径前缀（向后兼容）
        I18nKeyPath prefixPath = binaryAttr.getI18nPrefixPath();
        assertEquals("state.binary_attr.", prefixPath.getPathPrefix());
        assertEquals("", prefixPath.getLastSegment());
        assertEquals("state.binary_attr.", prefixPath.getFullPath());

        // 测试显示名称 - 使用getDisplayName()间接测试路径
        String displayName = binaryAttr.getDisplayName();
        assertNotNull(displayName);

        // 测试选项路径
        I18nKeyPath optionPath = binaryAttr.getI18nOptionPathPrefix();
        assertEquals("state.binary_attr.status_options", optionPath.getFullPath());
    }

    @Test
    public void testModbusShortAttributeWithoutDevice() {
        // 测试未绑定设备时的路径前缀（向后兼容）
        I18nKeyPath prefixPath = shortAttr.getI18nPrefixPath();
        assertEquals("state.numeric_attr.", prefixPath.getPathPrefix());
        assertEquals("", prefixPath.getLastSegment());
        assertEquals("state.numeric_attr.", prefixPath.getFullPath());

        // 测试显示名称 - 使用getDisplayName()间接测试路径
        String displayName = shortAttr.getDisplayName();
        assertNotNull(displayName);
    }

    @Test
    public void testModbusFloatAttributeWithDevice() {
        // 设置模拟设备
        when(mockDevice.getI18nPrefix()).thenReturn(new I18nKeyPath("devices.test_device.", ""));
        floatAttr.setDevice(mockDevice);

        // 测试绑定设备后的路径前缀（新的设备分组路径）
        I18nKeyPath prefixPath = floatAttr.getI18nPrefixPath();
        assertEquals("devices.test_device.", prefixPath.getPathPrefix());
        assertEquals("", prefixPath.getLastSegment());
        assertEquals("devices.test_device.", prefixPath.getFullPath());

        // 测试显示名称 - 使用getDisplayName()间接测试路径
        String displayName = floatAttr.getDisplayName();
        assertNotNull(displayName);
    }

    @Test
    public void testModbusBinaryAttributeWithDevice() {
        // 设置模拟设备
        when(mockDevice.getI18nPrefix()).thenReturn(new I18nKeyPath("devices.test_device.", ""));
        binaryAttr.setDevice(mockDevice);

        // 测试绑定设备后的路径前缀（BinaryAttribute继承自AttributeBase，使用新的设备分组路径逻辑）
        I18nKeyPath prefixPath = binaryAttr.getI18nPrefixPath();
        assertEquals("state.binary_attr.", prefixPath.getPathPrefix());
        assertEquals("", prefixPath.getLastSegment());
        assertEquals("state.binary_attr.", prefixPath.getFullPath());

        // 测试显示名称 - 使用getDisplayName()间接测试路径
        String displayName = binaryAttr.getDisplayName();
        assertNotNull(displayName);

        // 测试选项路径（基于显示名称路径构建）
        I18nKeyPath optionPath = binaryAttr.getI18nOptionPathPrefix();
        assertEquals("devices.test_device.status_options", optionPath.getFullPath());
    }

    @Test
    public void testModbusShortAttributeWithDevice() {
        // 设置模拟设备
        when(mockDevice.getI18nPrefix()).thenReturn(new I18nKeyPath("devices.test_device.", ""));
        shortAttr.setDevice(mockDevice);

        // 测试绑定设备后的路径前缀（新的设备分组路径）
        I18nKeyPath prefixPath = shortAttr.getI18nPrefixPath();
        assertEquals("devices.test_device.", prefixPath.getPathPrefix());
        assertEquals("", prefixPath.getLastSegment());
        assertEquals("devices.test_device.", prefixPath.getFullPath());

        // 测试显示名称 - 使用getDisplayName()间接测试路径
        String displayName = shortAttr.getDisplayName();
        assertNotNull(displayName);
    }

    @Test
    public void testDeviceWithTypeName() {
        // 设置模拟设备
        when(mockDevice.getTypeName()).thenReturn("modbus_device");
        when(mockDevice.getI18nPrefix()).thenReturn(new I18nKeyPath("devices.modbus_device.", ""));
        floatAttr.setDevice(mockDevice);

        // 验证路径包含正确的设备类型名称
        I18nKeyPath prefixPath = floatAttr.getI18nPrefixPath();
        assertEquals("devices.modbus_device.", prefixPath.getFullPath());
    }

    @Test
    public void testBackwardCompatibility() {
        // 确保向后兼容性：未绑定设备时使用原有路径结构

        // 测试数值属性路径前缀
        assertEquals("state.numeric_attr.", floatAttr.getI18nPrefixPath().getFullPath());
        assertEquals("state.numeric_attr.", shortAttr.getI18nPrefixPath().getFullPath());

        // 测试二值属性路径前缀
        assertEquals("state.binary_attr.", binaryAttr.getI18nPrefixPath().getFullPath());
        assertEquals("state.binary_attr.status_options", binaryAttr.getI18nOptionPathPrefix().getFullPath());
    }

    @Test
    public void testPathTransformation() {
        // 测试路径转换的一致性
        when(mockDevice.getI18nPrefix()).thenReturn(new I18nKeyPath("devices.test_device.", ""));
        floatAttr.setDevice(mockDevice);

        // 验证路径前缀转换的正确性
        I18nKeyPath prefixPath = floatAttr.getI18nPrefixPath();

        // 测试路径方法
        assertEquals("devices.test_device.", prefixPath.getPathPrefix());
        assertEquals("", prefixPath.getLastSegment());
        assertEquals("devices.test_device.", prefixPath.getFullPath());
        assertEquals("devices.test_device.", prefixPath.getI18nPath());

        // 测试路径修改方法
        I18nKeyPath modifiedPath = prefixPath.withSuffix("_unit");
        assertEquals("devices.test_device.", modifiedPath.getPathPrefix());
        assertEquals("_unit", modifiedPath.getLastSegment());
        assertEquals("devices.test_device._unit", modifiedPath.getFullPath());
    }

    @Test
    public void testOptionDictionaryWithDevice() {
        // 测试选项字典功能
        when(mockDevice.getI18nPrefix()).thenReturn(new I18nKeyPath("devices.test_device.", ""));
        binaryAttr.setDevice(mockDevice);

        // 测试选项字典（这个测试需要实际的i18n配置，这里只测试路径）
        I18nKeyPath optionPath = binaryAttr.getI18nOptionPathPrefix();
        assertEquals("devices.test_device.status_options", optionPath.getFullPath());

        // 测试具体选项路径
        I18nKeyPath onOptionPath = optionPath.addLastSegment("on");
        assertEquals("devices.test_device.status_options.on", onOptionPath.getFullPath());

        I18nKeyPath offOptionPath = optionPath.addLastSegment("off");
        assertEquals("devices.test_device.status_options.off", offOptionPath.getFullPath());
    }
}
