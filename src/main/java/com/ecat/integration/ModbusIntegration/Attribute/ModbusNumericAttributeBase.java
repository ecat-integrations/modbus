package com.ecat.integration.ModbusIntegration.Attribute;

import java.util.function.Function;

import com.ecat.core.State.NumberAttribute;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.UnitInfo;
import com.ecat.core.State.AttrChangedCallbackParams;
import com.ecat.core.Utils.DynamicConfig.ConfigDefinition;
import com.ecat.core.I18n.I18nKeyPath;
import com.ecat.integration.ModbusIntegration.ModbusSource;

/**
 * Modbus数值属性基类，提供统一的I18n和验证支持
 *
 * 该类作为Facade模式，为所有Modbus数值属性类提供通用的功能：
 * - 统一的ModbusSource管理
 * - 统一的I18n路径前缀
 * - 默认的验证规则（不限制float和short类型）
 * - 减少重复代码
 *
 * 泛型类型限制：T 必须是 Number 类型且可比较，确保只有数值类型可以继承
 *
 * @param <T> 属性值类型，必须是 Number 且 Comparable
 *
 * @Author coffee
 */
public abstract class ModbusNumericAttributeBase<T extends Number & Comparable<T>> extends NumberAttribute<T> {

    protected ModbusSource modbusSource; // Modbus源

    /**
     * 构造函数：使用i18n显示displayName
     *
     * @param attributeID 属性ID，唯一标识符，小写
     * @param attrClass 属性类
     * @param nativeUnit 原始单位
     * @param displayUnit 显示单位
     * @param displayPrecision 显示精度
     * @param unitChangeable 单位是否可变更
     * @param valueChangeable 值是否可变更
     * @param modbusSource Modbus源
     */
    protected ModbusNumericAttributeBase(String attributeID, AttributeClass attrClass,
            UnitInfo nativeUnit, UnitInfo displayUnit, int displayPrecision,
            boolean unitChangeable, boolean valueChangeable,
            ModbusSource modbusSource) {
        super(attributeID, attrClass, nativeUnit, displayUnit, displayPrecision,
              unitChangeable, valueChangeable);
        this.modbusSource = modbusSource;
    }

    /**
     * 构造函数：使用i18n显示displayName，支持回调
     *
     * @param attributeID 属性ID，唯一标识符，小写
     * @param attrClass 属性类
     * @param nativeUnit 原始单位
     * @param displayUnit 显示单位
     * @param displayPrecision 显示精度
     * @param unitChangeable 单位是否可变更
     * @param valueChangeable 值是否可变更
     * @param onChangedCallback 值变化回调函数
     * @param modbusSource Modbus源
     */
    protected ModbusNumericAttributeBase(String attributeID, AttributeClass attrClass,
            UnitInfo nativeUnit, UnitInfo displayUnit, int displayPrecision,
            boolean unitChangeable, boolean valueChangeable,
            Function<AttrChangedCallbackParams<T>, java.util.concurrent.CompletableFuture<Boolean>> onChangedCallback,
            ModbusSource modbusSource) {
        super(attributeID, attrClass, nativeUnit, displayUnit, displayPrecision,
              unitChangeable, valueChangeable, onChangedCallback);
        this.modbusSource = modbusSource;
    }

    /**
     * 同时支持用户设置显示名称displayName和I18n的构造函数
     * 适合参数名称不固定的设备的国际化支持，比如数据采集转换模块的不同通道需要用户设置名称，以用户语言文字设置的显示名称为高优先级
     *
     * @param attributeID 属性ID
     * @param displayName 用户设置的显示名称，优先级高
     * @param attrClass 属性类
     * @param nativeUnit 原始单位
     * @param displayUnit 显示单位
     * @param displayPrecision 显示精度
     * @param unitChangeable 单位是否可变更
     * @param valueChangeable 值是否可变更
     * @param modbusSource Modbus源
     */
    protected ModbusNumericAttributeBase(String attributeID, String displayName, AttributeClass attrClass,
            UnitInfo nativeUnit, UnitInfo displayUnit, int displayPrecision,
            boolean unitChangeable, boolean valueChangeable,
            ModbusSource modbusSource) {
        super(attributeID, displayName, attrClass, nativeUnit, displayUnit, displayPrecision,
              unitChangeable, valueChangeable);
        this.modbusSource = modbusSource;
    }

    /**
     * 同时支持用户设置显示名称displayName和I18n的构造函数
     * 适合参数名称不固定的设备的国际化支持，比如数据采集转换模块的不同通道需要用户设置名称，以用户语言文字设置的显示名称为高优先级
     *
     * @param attributeID 属性ID
     * @param displayName 用户设置的显示名称，优先级高
     * @param attrClass 属性类
     * @param nativeUnit 原始单位
     * @param displayUnit 显示单位
     * @param displayPrecision 显示精度
     * @param unitChangeable 单位是否可变更
     * @param valueChangeable 值是否可变更
     * @param onChangedCallback 值变化回调函数
     * @param modbusSource Modbus源
     */
    protected ModbusNumericAttributeBase(String attributeID, String displayName, AttributeClass attrClass,
            UnitInfo nativeUnit, UnitInfo displayUnit, int displayPrecision,
            boolean unitChangeable, boolean valueChangeable,
            Function<AttrChangedCallbackParams<T>, java.util.concurrent.CompletableFuture<Boolean>> onChangedCallback,
            ModbusSource modbusSource) {
        super(attributeID, displayName, attrClass, nativeUnit, displayUnit, displayPrecision,
              unitChangeable, valueChangeable, onChangedCallback);
        this.modbusSource = modbusSource;
    }

    /**
     * 获取I18n路径前缀
     * 支持设备分组的新路径结构和向后兼容
     */
    @Override
    public I18nKeyPath getI18nPrefixPath() {
        // 优先尝试设备分组路径
        if(device != null && device.getI18nPrefix() != null){
            return device.getI18nPrefix();
        }else{
            // 回退到原有路径结构
            return new I18nKeyPath("state.numeric_attr.", "");
        }
    }

    /**
     * 获取Modbus源
     * @return Modbus源实例
     */
    public ModbusSource getModbusSource() {
        return modbusSource;
    }

    /**
     * 设置Modbus源
     * @param modbusSource 新的Modbus源
     */
    public void setModbusSource(ModbusSource modbusSource) {
        this.modbusSource = modbusSource;
    }

    /**
     * 获取值定义
     * 默认实现不限制验证范围，适用于float和short类型
     * 子类可以根据需要重写此方法添加特定的验证规则
     */
    @Override
    public ConfigDefinition getValueDefinition() {
        // Modbus数值属性默认不限制验证范围
        // 子类可以根据需要重写此方法添加特定的验证规则
        return null;
    }
}
