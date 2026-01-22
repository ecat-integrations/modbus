package com.ecat.integration.ModbusIntegration.Attribute;

import java.util.List;

import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.LinearConversionAttribute;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.EndianConverter.EndianConverter;

/**
 * Modbus线性转换属性类
 * 
 * <p>设计目标：</p>
 * <ul>
 *   <li>继承LinearConversionAttribute的线性转换功能</li>
 *   <li>集成Modbus通信能力（读取Input Registers）</li>
 *   <li>支持配置驱动的量程设置</li>
 *   <li>适用于Modbus采集卡的模拟量输入通道</li>
 * </ul>
 * 
 * <p>典型应用场景：</p>
 * <ul>
 *   <li>DAM0888A等采集卡的模拟量输入</li>
 *   <li>4-20mA传感器接入（通过250Ω电阻转为1-5V）</li>
 *   <li>0-10V直接电压输入</li>
 *   <li>支持单段和多段线性转换</li>
 * </ul>
 * 
 * <p>工作原理：</p>
 * <pre>
 * Modbus读取 → 原始寄存器值
 *     ↓
 * EndianConverter转换 + ScaleFactor缩放
 *     ↓
 * 电压值（如3.5V）
 *     ↓
 * LinearConversionAttribute转换
 *     ↓
 * 工程量（如5.0MPa）
 * </pre>
 * 
 * @author coffee
 */
public class ModbusLinearConversionAttribute extends LinearConversionAttribute {

    private ModbusSource modbusSource;      // Modbus数据源
    private short registerAddress;          // 输入寄存器地址
    private EndianConverter endianConverter; // 字节序转换器
    private float scaleFactor;              // 缩放因子（寄存器值 × scaleFactor = 实际电压值）

    /**
     * 单段转换构造函数
     * 
     * @param attributeID 属性ID
     * @param attrClass 属性类型
     * @param inputMin 输入最小值（电压）
     * @param inputMax 输入最大值（电压）
     * @param outputMin 输出最小值（工程量）
     * @param outputMax 输出最大值（工程量）
     * @param inputUnitEnumName 输入单位枚举名称
     * @param outputUnitEnumName 输出单位枚举名称
     * @param displayPrecision 显示精度
     * @param modbusSource Modbus数据源
     * @param registerAddress 输入寄存器地址
     * @param endianConverter 字节序转换器
     * @param scaleFactor 缩放因子（如1000表示寄存器值÷1000）
     */
    public ModbusLinearConversionAttribute(
            String attributeID, 
            AttributeClass attrClass,
            Double inputMin, 
            Double inputMax,
            Double outputMin, 
            Double outputMax,
            String inputUnitEnumName, 
            String outputUnitEnumName,
            int displayPrecision,
            ModbusSource modbusSource,
            short registerAddress,
            EndianConverter endianConverter,
            float scaleFactor) {
        super(attributeID, attrClass, 
              inputMin, inputMax, outputMin, outputMax,
              inputUnitEnumName, outputUnitEnumName, 
              displayPrecision, false);
        
        this.modbusSource = modbusSource;
        this.registerAddress = registerAddress;
        this.endianConverter = endianConverter;
        this.scaleFactor = scaleFactor;
    }

    /**
     * 单段转换带显示名称构造函数
     */
    public ModbusLinearConversionAttribute(
            String attributeID,
            String displayName,
            AttributeClass attrClass,
            Double inputMin,
            Double inputMax,
            Double outputMin,
            Double outputMax,
            String inputUnitEnumName,
            String outputUnitEnumName,
            int displayPrecision,
            ModbusSource modbusSource,
            short registerAddress,
            EndianConverter endianConverter,
            float scaleFactor) {
        super(attributeID, displayName, attrClass,
              inputMin, inputMax, outputMin, outputMax,
              inputUnitEnumName, outputUnitEnumName,
              displayPrecision, false);
        
        this.modbusSource = modbusSource;
        this.registerAddress = registerAddress;
        this.endianConverter = endianConverter;
        this.scaleFactor = scaleFactor;
    }

    /**
     * 多段转换构造函数
     * 
     * @param attributeID 属性ID
     * @param attrClass 属性类型
     * @param segments 线性转换段列表
     * @param inputUnitEnumName 输入单位枚举名称
     * @param outputUnitEnumName 输出单位枚举名称
     * @param displayPrecision 显示精度
     * @param modbusSource Modbus数据源
     * @param registerAddress 输入寄存器地址
     * @param endianConverter 字节序转换器
     * @param scaleFactor 缩放因子
     */
    public ModbusLinearConversionAttribute(
            String attributeID,
            AttributeClass attrClass,
            List<LinearSegment> segments,
            String inputUnitEnumName,
            String outputUnitEnumName,
            int displayPrecision,
            ModbusSource modbusSource,
            short registerAddress,
            EndianConverter endianConverter,
            float scaleFactor) {
        super(attributeID, attrClass, segments,
              inputUnitEnumName, outputUnitEnumName,
              displayPrecision, false);
        
        this.modbusSource = modbusSource;
        this.registerAddress = registerAddress;
        this.endianConverter = endianConverter;
        this.scaleFactor = scaleFactor;
    }

    /**
     * 多段转换带显示名称构造函数
     */
    public ModbusLinearConversionAttribute(
            String attributeID,
            String displayName,
            AttributeClass attrClass,
            List<LinearSegment> segments,
            String inputUnitEnumName,
            String outputUnitEnumName,
            int displayPrecision,
            ModbusSource modbusSource,
            short registerAddress,
            EndianConverter endianConverter,
            float scaleFactor) {
        super(attributeID, displayName, attrClass, segments,
              inputUnitEnumName, outputUnitEnumName,
              displayPrecision, false);
        
        this.modbusSource = modbusSource;
        this.registerAddress = registerAddress;
        this.endianConverter = endianConverter;
        this.scaleFactor = scaleFactor;
    }

    /**
     * 从Modbus读取的寄存器值更新属性
     * 
     * @param registerValue 寄存器原始值
     * @return 更新是否成功
     */
    public boolean updateValue(short registerValue) {
        // 1. 字节序转换（short to int）
        int convertedValue = endianConverter.shortToInt(registerValue);
        
        // 2. 应用缩放因子，得到实际电压值
        double voltageValue = convertedValue / scaleFactor;
        
        // 3. 调用父类的updateValue，触发线性转换
        // LinearConversionAttribute会自动将电压值转换为工程量
        return super.updateValue(voltageValue);
    }

    /**
     * 直接更新电压值（用于测试或特殊场景）
     */
    @Override
    public boolean updateValue(Double voltageValue) {
        return super.updateValue(voltageValue);
    }

    // Getter方法
    public ModbusSource getModbusSource() {
        return modbusSource;
    }

    public short getRegisterAddress() {
        return registerAddress;
    }

    public EndianConverter getEndianConverter() {
        return endianConverter;
    }

    public float getScaleFactor() {
        return scaleFactor;
    }
}

