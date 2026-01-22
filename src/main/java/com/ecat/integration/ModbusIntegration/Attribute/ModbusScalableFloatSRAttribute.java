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
 * ModbusScalableFloatSRAttribute class
 * 
 * 处理Modbus中值为实际值指定倍数的short类型数据，内部存储为float类型
 * 设备值 = 实际值 * scaleFactor，因此读取时需要除以scaleFactor，写入时需要乘以scaleFactor
 * 支持大小端字节序转换，使用一个寄存器（16位）存储实际值
 * 
 * @author coffee
 * 
 * @see ModbusSource
 * @see UnitInfo
 * @see EndianConverter
 * 
 * @example <pre>
 * // 创建大端模式、10倍缩放的温度属性（寄存器地址0x1001）
 * ModbusSource modbusSource = new ModbusSource("192.168.1.10", 502);
 * EndianConverter bigEndianConverter = new BigEndianConverter(); // 大端实现
 * 
 * ModbusScalableFloatSRAttribute temperatureAttr = new ModbusScalableFloatSRAttribute(
 *     "temperature", 
 *     "温度",
 *     AttributeClass.TEMPERATURE,
 *     TemperatureUnit.CELSIUS, 
 *     TemperatureUnit.CELSIUS, 
 *     1, 
 *     true, 
 *     true,
 *     modbusSource, 
 *     (short)0x1001,
 *     bigEndianConverter,
 *     10.0f
 * );
 * 
 * // 创建小端模式、100倍缩放的压力属性（寄存器地址0x1002）
 * EndianConverter littleEndianConverter = new LittleEndianConverter(); // 小端实现
 * ModbusScalableFloatSRAttribute pressureAttr = new ModbusScalableFloatSRAttribute(
 *     "pressure", 
 *     "压力",
 *     AttributeClass.PRESSURE,
 *     PressureUnit.KPA, 
 *     PressureUnit.MPA, 
 *     2, 
 *     true, 
 *     true,
 *     modbusSource, 
 *     (short)0x1002,
 *     littleEndianConverter,
 *     100.0f
 * );
 * </pre>
 */
public class ModbusScalableFloatSRAttribute extends ModbusNumericAttributeBase<Float> {

    @Getter
    private Short registerAddress; // 目标寄存器地址
    private final EndianConverter endianConverter; // 字节序转换器
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
    public ModbusScalableFloatSRAttribute(String attributeID, AttributeClass attrClass,
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
    public ModbusScalableFloatSRAttribute(String attributeID, String displayName, AttributeClass attrClass,
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

    /**
     * 设置属性值
     * 实际写入Modbus寄存器的值为：newValue * scaleFactor（经过字节序转换）
     * 
     * @param newValue 新的属性值
     * @return 异步操作结果，成功返回true，失败返回false
     */
    @Override
    protected CompletableFuture<Boolean> setValue(Float newValue) {
        if (!valueChangeable) {
            return CompletableFuture.completedFuture(false);
        }
        
        // 计算缩放后的设备值
        float scaledValue = newValue * scaleFactor;
        
        // 将浮点数转换为int（仅取整数部分）
        int intValue = (int) scaledValue;
        
        // 使用字节序转换器将int转换为short
        short writeValue = endianConverter.intToShort(intValue);
        
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
            return source.writeRegister(registerAddress, writeValue)
                    .thenCompose((response) -> {
                        if (response == null || response.isException()) {
                            throw new RuntimeException("命令下发失败: " + response.getExceptionMessage());
                        }
                        return super.setValue(newValue);
                    });
        });
    }

    /**
     * 设备从Modbus读取的寄存器值更新属性值
     * 实际属性值为：(寄存器值 / scaleFactor)（经过字节序转换）
     * @important 仅为设备数据更新使用，不要用于用户侧操作
     * 
     * @param registerValue 从Modbus寄存器读取的值
     * @return 如果值有变化返回true，否则返回false
     */
    public boolean updateValue(short registerValue) {
        // 使用字节序转换器将short转换为int
        int deviceValue = endianConverter.shortToInt(registerValue);
        
        // 应用缩放因子得到实际值
        float actualValue = deviceValue / scaleFactor;
        
        return super.updateValue(actualValue);
    }

    /**
     * 设备从Modbus读取的寄存器值更新属性值，并设置状态
     * 实际属性值为：(寄存器值 / scaleFactor)（经过字节序转换）
     * @important 仅为设备数据更新使用，不要用于用户侧操作
     * 
     * @param registerValue 从Modbus寄存器读取的值
     * @param status        属性状态
     * @return 如果值有变化返回true，否则返回false
     */
    public boolean updateValue(short registerValue, AttributeStatus status) {
        int deviceValue = endianConverter.shortToInt(registerValue);
        float actualValue = deviceValue / scaleFactor;
        return super.updateValue(actualValue, status);
    }

    @Override
    public String getDisplayValue(UnitInfo toUnit) {
        if (value == null) return null;
        
        // 处理单位转换以获取显示值
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
}
