package com.ecat.integration.ModbusIntegration.Attribute;

import java.util.concurrent.CompletableFuture;

import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.UnitInfo;
import com.ecat.core.Utils.NumberFormatter;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.ModbusTransactionStrategy;
import com.ecat.integration.ModbusIntegration.EndianConverter.EndianConverter;
import lombok.Getter;

/**
 * ModbusScalableFloatDRAttribute class
 * 
 * This class represents a Modbus attribute that can be read and written as a float value with scaling support.
 * Float values are stored in two Modbus registers (16-bit each) and are formatted as int32.
 * It extends the ModbusNumericAttributeBase class and provides methods to manage the float attribute with scaling capabilities.
 * 
 * The scaling factor allows converting between device values and actual values using the formula:
 * Device Value = Actual Value * scaleFactor
 * Actual Value = Device Value / scaleFactor
 * 
 * this read register value is a float value, but it is stored in two Modbus registers (16-bit each) and format is int32.
 * 
 * @Author coffee
 * 
 * @see ModbusSource
 * @see UnitInfo
 * @see EndianConverter
 * 
 * @example <pre>
 * // 创建10倍缩放的温度属性（寄存器地址0x46）
 * ModbusSource modbusSource = new ModbusSource("192.168.1.10", 502);
 * EndianConverter endianConverter = new DefaultEndianConverter(); // 假设存在默认实现
 * 
 * ModbusScalableFloatDRAttribute temperatureAttr = new ModbusScalableFloatDRAttribute(
 *     "temperature", 
 *     AttributeClass.TEMPERATURE,
 *     TemperatureUnit.CELSIUS, 
 *     TemperatureUnit.CELSIUS, 
 *     1, 
 *     true, 
 *     true,
 *     modbusSource, 
 *     (short)0x46,
 *     endianConverter,
 *     10.0f
 * );
 * 
 * // 用户写入操作示例 - 设置实际温度为25.5°C
 * temperatureAttr.setDisplayValue(25.5f).thenAccept(success -> {
 *     if (success) {
 *         System.out.println("温度设置成功");
 *     } else {
 *         System.out.println("温度设置失败");
 *     }
 * });
 * 
 * // 设备读取操作示例（假设通过回调更新值）
 * short word1 = (short) 0x5678; // 示例寄存器值1
 * short word2 = (short) 0x1234; // 示例寄存器值2
 * temperatureAttr.updateValue(word1, word2);
 * System.out.println("当前温度: " + temperatureAttr.getValue() + "°C"); ==> 0x12345678 / 10.0f
 * 
 * // 创建100倍缩放的压力属性
 * ModbusScalableFloatDRAttribute pressureAttr = new ModbusScalableFloatDRAttribute(
 *     "pressure", 
 *     AttributeClass.PRESSURE,
 *     PressureUnit.KPA, 
 *     PressureUnit.MPA, 
 *     2, 
 *     true, 
 *     true,
 *     modbusSource, 
 *     (short)0x47,
 *     endianConverter,
 *     100.0f
 * );
 * 
 * // 压力单位转换示例
 * System.out.println("压力显示值: " + pressureAttr.getDisplayValue(PressureUnit.KPA) + "kPa");
 * </pre>
 */
public class ModbusScalableFloatDRAttribute extends ModbusNumericAttributeBase<Float> {

    @Getter
    private Short registerAddress; // 目标寄存器地址
    private final EndianConverter endianConverter;
    private final float scaleFactor; // 缩放因子

    /**
     * 构造函数：使用i18n显示displayName
     *
     * @param attributeID       属性唯一标识符
     * @param attrClass         属性分类
     * @param nativeUnit        原始单位
     * @param displayUnit       显示单位
     * @param displayPrecision  显示精度（小数位数）
     * @param unitChangeable    单位是否可更改
     * @param valueChangeable   值是否可更改
     * @param modbusSource      Modbus源
     * @param registerAddress   寄存器地址
     * @param endianConverter   字节序转换器
     * @param scaleFactor       缩放因子（如10、100）
     */
    public ModbusScalableFloatDRAttribute(String attributeID, AttributeClass attrClass,
            UnitInfo nativeUnit, UnitInfo displayUnit, int displayPrecision,
            boolean unitChangeable, boolean valueChangeable,
            ModbusSource modbusSource, Short registerAddress,
            EndianConverter endianConverter, float scaleFactor) {
        super(attributeID, attrClass, nativeUnit, displayUnit, displayPrecision,
              unitChangeable, valueChangeable, modbusSource);
        this.registerAddress = registerAddress;
        this.endianConverter = endianConverter;
        this.scaleFactor = scaleFactor;
    }

    /**
     * 同时支持用户设置显示名称displayName和I18n的构造函数
     * 适合参数名称不固定的设备的国际化支持，比如数据采集转换模块的不同通道需要用户设置名称，以用户语言文字设置的显示名称为高优先级
     *
     * @param attributeID       属性唯一标识符
     * @param displayName       用户设置的显示名称，优先级高
     * @param attrClass         属性分类
     * @param nativeUnit        原始单位
     * @param displayUnit       显示单位
     * @param displayPrecision  显示精度（小数位数）
     * @param unitChangeable    单位是否可更改
     * @param valueChangeable   值是否可更改
     * @param modbusSource      Modbus源
     * @param registerAddress   寄存器地址
     * @param endianConverter   字节序转换器
     * @param scaleFactor       缩放因子（如10、100），写入modbusValue = realValue * scaleFactor;
     */
    public ModbusScalableFloatDRAttribute(String attributeID, String displayName, AttributeClass attrClass,
            UnitInfo nativeUnit, UnitInfo displayUnit, int displayPrecision,
            boolean unitChangeable, boolean valueChangeable,
            ModbusSource modbusSource, Short registerAddress,
            EndianConverter endianConverter, float scaleFactor) {
        super(attributeID, displayName, attrClass, nativeUnit, displayUnit, displayPrecision,
              unitChangeable, valueChangeable, modbusSource);
        this.registerAddress = registerAddress;
        this.endianConverter = endianConverter;
        this.scaleFactor = scaleFactor;
    }

    @Override
    protected CompletableFuture<Boolean> setValue(Float newValue) {
        if (!valueChangeable) {
            return CompletableFuture.completedFuture(false);
        }
        
        // 计算缩放后的设备值
        float scaledValue = newValue * scaleFactor;
        
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
            short[] resultShorts = endianConverter.intToShorts((int)scaledValue);
            return source.writeRegisters(registerAddress, resultShorts)
                    .thenCompose((response) -> {
                        if (response == null || response.isException()) {
                            throw new RuntimeException("命令下发失败: " + response.getExceptionMessage());
                        }
                        return super.setValue(newValue);
                    });
        });
    }

    /**
     * 从Modbus读取的寄存器值更新属性值
     * 实际属性值为：(寄存器转换后的float值) / scaleFactor
     * 
     * @param word1 第一个寄存器值
     * @param word2 第二个寄存器值
     * @return 如果值有变化返回true，否则返回false
     */
    public boolean updateValue(short word1, short word2) {
        // 从寄存器值转换为float
        int deviceValue = endianConverter.shortsToInt(word1, word2);
        
        // 应用缩放因子得到实际值
        Float actualValue = deviceValue / scaleFactor;
        
        return super.updateValue(actualValue);
    }

    /**
     * 从Modbus读取的寄存器值更新属性值
     * 实际属性值为：(寄存器转换后的float值) / scaleFactor
     *
     * @param word1 第一个寄存器值
     * @param word2 第二个寄存器值
     * @param status        属性状态
     * @return 如果值有变化返回true，否则返回false
     */
    public boolean updateValue(short word1, short word2, AttributeStatus status) {
        // 从寄存器值转换为float
        int deviceValue = endianConverter.shortsToInt(word1, word2);
        // 应用缩放因子得到实际值
        Float actualValue = deviceValue / scaleFactor;
        return super.updateValue(actualValue, status);
    }

    @Override
    public String getDisplayValue(UnitInfo toUnit) {
        if (value == null) return null;
        
        Double displayValue;
        if (nativeUnit != null && toUnit != null && 
            nativeUnit.getClass().equals(toUnit.getClass())) {
            displayValue = value.doubleValue() * nativeUnit.convertUnit(toUnit);
            return NumberFormatter.formatValue(displayValue, displayPrecision);
        } else if (nativeUnit == null || toUnit == null) {
            // 缺少任一单位，显示原值
            return NumberFormatter.formatValue(value, displayPrecision);
        } else {
            // 单位都存在但无法转换
            throw new RuntimeException("不支持的单位转换");
        }
    }

    @Override
    protected Float convertFromUnitImp(Float value, UnitInfo fromUnit) {
        if (value == null) return null;

        if (fromUnit == null || nativeUnit == null) {
            return value; // 如果没有指定单位，直接返回原始值
        }
        // 如果是同class转换，直接转换
        if(nativeUnit.getClass().equals(fromUnit.getClass())){
            // 根据 newUnit 的转换系数转换
            return value * fromUnit.convertUnit(nativeUnit).floatValue();
        }
        // 单位类型不同或未定义，直接使用原值
        else{
            return value;
        }
    }

    @Override
    protected Float convertToType(double value) {
        return (float) value;
    }
}
