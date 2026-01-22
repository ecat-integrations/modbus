package com.ecat.integration.ModbusIntegration.Attribute;

import java.util.concurrent.CompletableFuture;

import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.UnitInfo;
import com.ecat.core.Utils.NumberFormatter;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.ModbusTransactionStrategy;
import com.ecat.integration.ModbusIntegration.EndianConverter.EndianConverter;

/**
 * ModbusFloatAttribute class
 *
 * This class represents a Modbus attribute that can be read and written as a float value. like IEEE 754 float.
 * It extends the ModbusNumericAttributeBase class and provides methods to manage the float attribute.
 *
 * This class is suitable for attributes that represent float values such as
 * temperature, pressure, etc.
 *
 * @Author coffee
 */
public class ModbusFloatAttribute extends ModbusNumericAttributeBase<Float> {

    private Short registerAddress; // 目标寄存器地址（0x46）
    private final EndianConverter endianConverter;
    // private final short registerNumber = 2; // 寄存器数量

    /**
     * 构造函数：使用i18n显示displayName
     *
     * @param attributeID
     * @param attrClass
     * @param nativeUnit
     * @param displayUnit
     * @param displayPrecision
     * @param unitChangeable
     * @param valueChangeable
     * @param modbusSource
     * @param registerAddress
     * @param endianConverter
     */
    public ModbusFloatAttribute(String attributeID, AttributeClass attrClass,
            UnitInfo nativeUnit, UnitInfo displayUnit, int displayPrecision,
            boolean unitChangeable, boolean valueChangeable,
            ModbusSource modbusSource, Short registerAddress,
            EndianConverter endianConverter) {
        super(attributeID, attrClass, nativeUnit, displayUnit, displayPrecision,
              unitChangeable, valueChangeable, modbusSource);
        this.registerAddress = registerAddress;
        this.endianConverter = endianConverter;
    }

    /**
     * 同时支持用户设置显示名称displayName和I18n的构造函数
     * 适合参数名称不固定的设备的国际化支持，比如数据采集转换模块的不同通道需要用户设置名称，以用户语言文字设置的显示名称为高优先级
     *
     * @param attributeID
     * @param displayName 用户设置的显示名称，优先级高
     * @param attrClass
     * @param nativeUnit
     * @param displayUnit
     * @param displayPrecision
     * @param unitChangeable
     * @param valueChangeable
     * @param modbusSource
     * @param registerAddress
     * @param endianConverter
     */
    public ModbusFloatAttribute(String attributeID, String displayName, AttributeClass attrClass,
            UnitInfo nativeUnit, UnitInfo displayUnit, int displayPrecision,
            boolean unitChangeable, boolean valueChangeable,
            ModbusSource modbusSource, Short registerAddress,
            EndianConverter endianConverter) {
        super(attributeID, displayName, attrClass, nativeUnit, displayUnit, displayPrecision,
              unitChangeable, valueChangeable, modbusSource);
        this.registerAddress = registerAddress;
        this.endianConverter = endianConverter;
    }

    @Override
    public CompletableFuture<Boolean> setValue(Float newValue) {
        if (!valueChangeable) {
            return CompletableFuture.completedFuture(false);
        }
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {

            short[] resultShorts = endianConverter.floatToShorts(newValue);
            return source.writeRegisters(registerAddress, resultShorts)
                    .thenCompose((response) -> {
                        if (response == null || response.isException()) {
                            throw new RuntimeException("命令下发失败: " + response.getExceptionMessage());
                        }
                        return super.setValue(newValue);
                    });
        });
    }

    public boolean updateValue(short word1, short word2) { // 参数名调整为更通用的word1/word2
        Float floatValue = endianConverter.shortsToFloat(word1, word2);
        return super.updateValue(floatValue);
    }

    @Override
    public String getDisplayValue(UnitInfo toUnit) {
        if (value == null) return null;

        Double displayValue;
        // 如果是同class转换，直接转换
        if (nativeUnit != null && toUnit != null && 
            nativeUnit.getClass().equals(toUnit.getClass())) {
                // 可转换
            displayValue = value.doubleValue() * nativeUnit.convertUnit(toUnit);
            return NumberFormatter.formatValue(displayValue, displayPrecision);
        } else if (nativeUnit == null || toUnit == null){
            // 缺少任一单位，显示原值
            return NumberFormatter.formatValue(value, displayPrecision);
        }
        else{
            // 单位都存在但无法转换
            // 是跨class转换，目前不支持，因为单位转换太广泛，如果有更明确的转换范围则定义新的属性类
            throw new RuntimeException("Invalid unit conversion");
        }
    }

    @Override
    public boolean updateValue(Float value) {
        // 更新属性值
        return super.updateValue(value);
    }

    @Override
    public boolean updateValue(Float value, AttributeStatus newStatus) {
        // 更新属性值
        return super.updateValue(value, newStatus);
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
}
