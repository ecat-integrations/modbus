package com.ecat.integration.ModbusIntegration.Attribute;

import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.LinearConversionAttribute.LinearSegment;
import com.ecat.integration.ModbusIntegration.EndianConverter.BigEndianConverter;
import com.ecat.integration.ModbusIntegration.ModbusSource;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * ModbusLinearConversionAttribute 单元测试
 * 
 * 测试内容：
 * 1. 单段线性转换（4-20mA → 0-10MPa）
 * 2. 多段线性转换（非线性传感器）
 * 3. Modbus寄存器值转换
 * 4. 字节序转换
 * 5. 缩放因子应用
 * 
 * @author coffee
 */
public class ModbusLinearConversionAttributeTest {

    private ModbusSource mockModbusSource;
    private BigEndianConverter endianConverter;

    @Before
    public void setUp() {
        // Mock ModbusSource
        mockModbusSource = mock(ModbusSource.class);
        
        // 使用真实的BigEndianConverter
        endianConverter = new BigEndianConverter();
    }

    /**
     * 测试1：单段转换 - 标准4-20mA压力传感器
     * 寄存器值 → 电压值 → 压力值
     */
    @Test
    public void testSingleSegmentConversion_PressureSensor() {
        // 创建4-20mA压力传感器（1-5V → 0-10MPa）
        ModbusLinearConversionAttribute attr = new ModbusLinearConversionAttribute(
            "pressure",
            AttributeClass.PRESSURE,
            1.0,  // inputMin: 1V (4mA × 250Ω)
            5.0,  // inputMax: 5V (20mA × 250Ω)
            0.0,  // outputMin: 0 MPa
            10.0, // outputMax: 10 MPa
            "VoltageUnit.VOLT",
            "PressureUnit.MPA",
            2,
            mockModbusSource,
            (short) 0x0000,
            endianConverter,
            1000.0f  // scaleFactor: 1000 (寄存器值 ÷ 1000 = 实际电压)
        );

        // 测试关键点转换
        
        // 寄存器值1000 → 1V → 0 MPa
        attr.updateValue((short) 1000);
        assertEquals("1000寄存器值应转换为0 MPa", 0.0, Double.parseDouble(attr.getDisplayValue()), 0.01);
        
        // 寄存器值3000 → 3V → 5 MPa
        attr.updateValue((short) 3000);
        assertEquals("3000寄存器值应转换为5 MPa", 5.0, Double.parseDouble(attr.getDisplayValue()), 0.01);
        
        // 寄存器值5000 → 5V → 10 MPa
        attr.updateValue((short) 5000);
        assertEquals("5000寄存器值应转换为10 MPa", 10.0, Double.parseDouble(attr.getDisplayValue()), 0.01);
    }

    /**
     * 测试2：单段转换 - 温度传感器
     * 1-5V → 0-100℃
     */
    @Test
    public void testSingleSegmentConversion_TemperatureSensor() {
        ModbusLinearConversionAttribute attr = new ModbusLinearConversionAttribute(
            "temperature",
            "反应釜温度",  // 带显示名称
            AttributeClass.TEMPERATURE,
            1.0, 5.0,
            0.0, 100.0,
            "VoltageUnit.VOLT",
            "TemperatureUnit.CELSIUS",
            1,
            mockModbusSource,
            (short) 0x0001,
            endianConverter,
            1000.0f
        );

        // 寄存器值2500 → 2.5V → 37.5℃
        attr.updateValue((short) 2500);
        assertEquals("2500寄存器值应转换为37.5℃", 37.5, Double.parseDouble(attr.getDisplayValue()), 0.1);
        
        // 检查显示名称
        assertEquals("显示名称应该匹配", "反应釜温度", attr.getDisplayName());
    }

    /**
     * 测试3：0-10V液位传感器
     * 0-10V → 0-5m
     */
    @Test
    public void testDirectVoltageInput_LevelSensor() {
        ModbusLinearConversionAttribute attr = new ModbusLinearConversionAttribute(
            "level",
            AttributeClass.DISTANCE,
            0.0, 10.0,   // 0-10V直接输入
            0.0, 5.0,    // 0-5米
            "VoltageUnit.VOLT",
            "DistanceUnit.M",
            2,
            mockModbusSource,
            (short) 0x0002,
            endianConverter,
            1000.0f
        );

        // 寄存器值7500 → 7.5V → 3.75m
        attr.updateValue((short) 7500);
        assertEquals("7500寄存器值应转换为3.75m", 3.75, Double.parseDouble(attr.getDisplayValue()), 0.01);
    }

    /**
     * 测试4：多段转换 - 非线性流量传感器
     */
    @Test
    public void testMultiSegmentConversion_FlowSensor() {
        // 创建多段转换配置
        List<LinearSegment> segments = new ArrayList<>();
        segments.add(new LinearSegment(1.0, 2.0, 0.0, 10.0));    // 小流量段
        segments.add(new LinearSegment(2.0, 4.0, 10.0, 80.0));   // 正常流量段
        segments.add(new LinearSegment(4.0, 5.0, 80.0, 100.0));  // 大流量段
        
        ModbusLinearConversionAttribute attr = new ModbusLinearConversionAttribute(
            "flow",
            AttributeClass.FLOW,
            segments,
            "VoltageUnit.VOLT",
            "LiterFlowUnit.L_PER_HOUR",
            2,
            mockModbusSource,
            (short) 0x0003,
            endianConverter,
            1000.0f
        );

        // 测试第一段：寄存器1500 → 1.5V → 5 L/h
        attr.updateValue((short) 1500);
        assertEquals("1500应转换为5", 5.0, Double.parseDouble(attr.getDisplayValue()), 0.01);
        
        // 测试第二段：寄存器3000 → 3V → 45 L/h
        attr.updateValue((short) 3000);
        assertEquals("3000应转换为45", 45.0, Double.parseDouble(attr.getDisplayValue()), 0.01);
        
        // 测试第三段：寄存器4500 → 4.5V → 90 L/h
        attr.updateValue((short) 4500);
        assertEquals("4500应转换为90", 90.0, Double.parseDouble(attr.getDisplayValue()), 0.01);
    }

    /**
     * 测试5：缩放因子验证
     * 测试不同缩放因子的正确性
     */
    @Test
    public void testScaleFactor() {
        // scaleFactor = 1000
        ModbusLinearConversionAttribute attr1000 = new ModbusLinearConversionAttribute(
            "test",
            AttributeClass.VALUE,
            0.0, 10.0,
            0.0, 100.0,
            "VoltageUnit.VOLT",
            "PressureUnit.MPA",
            2,
            mockModbusSource,
            (short) 0x0000,
            endianConverter,
            1000.0f
        );

        // 寄存器5000 ÷ 1000 = 5V → 50 (线性转换: 0-10V → 0-100)
        attr1000.updateValue((short) 5000);
        assertEquals("5000 ÷ 1000 = 5V → 50", 50.0, 
            Double.parseDouble(attr1000.getDisplayValue()), 0.01);
    }

    /**
     * 测试6：Getter方法
     */
    @Test
    public void testGetters() {
        ModbusLinearConversionAttribute attr = new ModbusLinearConversionAttribute(
            "test",
            AttributeClass.PRESSURE,
            1.0, 5.0, 0.0, 10.0,
            "VoltageUnit.VOLT",
            "PressureUnit.MPA",
            2,
            mockModbusSource,
            (short) 0x0000,
            endianConverter,
            1000.0f
        );

        assertEquals("Modbus源应该匹配", mockModbusSource, attr.getModbusSource());
        assertEquals("寄存器地址应该匹配", (short) 0x0000, attr.getRegisterAddress());
        assertEquals("字节序转换器应该匹配", endianConverter, attr.getEndianConverter());
        assertEquals("缩放因子应该匹配", 1000.0f, attr.getScaleFactor(), 0.01f);
    }

    /**
     * 测试7：直接电压值更新
     * 测试不通过Modbus寄存器，直接设置电压值的情况
     */
    @Test
    public void testDirectVoltageUpdate() {
        ModbusLinearConversionAttribute attr = new ModbusLinearConversionAttribute(
            "test",
            AttributeClass.PRESSURE,
            1.0, 5.0, 0.0, 10.0,
            "VoltageUnit.VOLT",
            "PressureUnit.MPA",
            2,
            mockModbusSource,
            (short) 0x0000,
            endianConverter,
            1000.0f
        );

        // 直接设置电压值（用于测试或特殊场景）
        attr.updateValue(3.0);  // 3V → 5 MPa
        assertEquals("3V应转换为5 MPa", 5.0, Double.parseDouble(attr.getDisplayValue()), 0.01);
    }

    /**
     * 测试8：边界值处理
     */
    @Test
    public void testBoundaryValues() {
        ModbusLinearConversionAttribute attr = new ModbusLinearConversionAttribute(
            "test",
            AttributeClass.PRESSURE,
            1.0, 5.0, 0.0, 10.0,
            "VoltageUnit.VOLT",
            "PressureUnit.MPA",
            2,
            mockModbusSource,
            (short) 0x0000,
            endianConverter,
            1000.0f
        );

        // 最小值
        attr.updateValue((short) 1000);  // 1V → 0 MPa
        assertEquals("最小值", 0.0, Double.parseDouble(attr.getDisplayValue()), 0.01);
        
        // 最大值
        attr.updateValue((short) 5000);  // 5V → 10 MPa
        assertEquals("最大值", 10.0, Double.parseDouble(attr.getDisplayValue()), 0.01);
    }

    /**
     * 测试9：属性类型映射
     */
    @Test
    public void testAttributeClassMapping() {
        // 压力传感器
        ModbusLinearConversionAttribute pressure = new ModbusLinearConversionAttribute(
            "pressure", AttributeClass.PRESSURE,
            1.0, 5.0, 0.0, 10.0,
            "VoltageUnit.VOLT", "PressureUnit.MPA", 2,
            mockModbusSource, (short) 0x0000, endianConverter, 1000.0f
        );
        assertEquals(AttributeClass.PRESSURE, pressure.getAttrClass());

        // 温度传感器
        ModbusLinearConversionAttribute temperature = new ModbusLinearConversionAttribute(
            "temperature", AttributeClass.TEMPERATURE,
            1.0, 5.0, 0.0, 100.0,
            "VoltageUnit.VOLT", "TemperatureUnit.CELSIUS", 1,
            mockModbusSource, (short) 0x0001, endianConverter, 1000.0f
        );
        assertEquals(AttributeClass.TEMPERATURE, temperature.getAttrClass());
    }
}

