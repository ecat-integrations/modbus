package com.ecat.integration.ModbusIntegration.Attribute;

import java.util.concurrent.CompletableFuture;

import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.UnitInfo;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.ModbusTransactionStrategy;

/**
 * Modbus 无符号 16 位寄存器属性。
 *
 * <p>寄存器字节模式是 0..65535，Java 侧统一用 Integer 保存工程值，
 * 避免 short 的符号位把 0x8000..0xFFFF 误读成负数。写入仍复用同一个
 * 16-bit 寄存器写帧。</p>
 * 
 * @author coffee
 */
public class ModbusUnsignedShortAttribute extends ModbusNumericAttributeBase<Integer> {

    /** 无符号 16 位可表示的最小值。 */
    private static final int MIN_UNSIGNED_SHORT = 0;

    /** 无符号 16 位可表示的最大值，同时是寄存器位模式 0xFFFF。 */
    private static final int MAX_UNSIGNED_SHORT = 0xFFFF;

    private Integer registerAddress; // 目标寄存器地址

    /**
     * 构造函数：使用i18n显示displayName
     *
     * @param attributeID 属性唯一标识符
     * @param attrClass 属性分类
     * @param nativeUnit 原始单位
     * @param displayUnit 显示单位
     * @param displayPrecision 显示精度
     * @param unitChangeable 单位是否可更改
     * @param valueChangeable 值是否可更改
     * @param modbusSource Modbus源
     * @param registerAddress 寄存器地址
     */
    public ModbusUnsignedShortAttribute(String attributeID, AttributeClass attrClass,
            UnitInfo nativeUnit, UnitInfo displayUnit, int displayPrecision,
            boolean unitChangeable, boolean valueChangeable,
            ModbusSource modbusSource, Integer registerAddress) {
        super(attributeID, attrClass, nativeUnit, displayUnit, displayPrecision,
              unitChangeable, valueChangeable, modbusSource);
        this.registerAddress = registerAddress;
    }

    /**
     * 同时支持用户设置显示名称displayName和I18n的构造函数
     *
     * @param attributeID 属性唯一标识符
     * @param displayName 用户设置的显示名称，优先级高
     * @param attrClass 属性分类
     * @param nativeUnit 原始单位
     * @param displayUnit 显示单位
     * @param displayPrecision 显示精度
     * @param unitChangeable 单位是否可更改
     * @param valueChangeable 值是否可更改
     * @param modbusSource Modbus源
     * @param registerAddress 寄存器地址
     */
    public ModbusUnsignedShortAttribute(String attributeID, String displayName, AttributeClass attrClass,
            UnitInfo nativeUnit, UnitInfo displayUnit, int displayPrecision,
            boolean unitChangeable, boolean valueChangeable,
            ModbusSource modbusSource, Integer registerAddress) {
        super(attributeID, displayName, attrClass, nativeUnit, displayUnit, displayPrecision,
              unitChangeable, valueChangeable, modbusSource);
        this.registerAddress = registerAddress;
    }

    // IO 载荷钩子（22 号 setValue final 化）：越界值在发帧前显式失败，不取低 16 位掩盖错误；
    // 合法 0..65535 原样交给 Modbus 单寄存器写帧，确认成功后的本地收尾由 final setValue 持有
    @Override
    protected CompletableFuture<Boolean> setValueImpl(Integer newValue) {
        requireUnsignedShort(newValue);
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source ->
            source.writeRegister(registerAddress, newValue)
                .thenCompose((response) -> {
                    if (response == null || response.isException()) {
                        throw new RuntimeException("命令下发失败: "
                                + (response != null ? response.getExceptionMessage() : "无响应(设备离线/传输失败)"));
                    }
                    return CompletableFuture.completedFuture(true);
                })
        );
    }

    /**
     * 从Modbus读取的单个寄存器更新属性值；short 的符号位不参与工程值语义。
     *
     * @param registerValue 从Modbus寄存器读取的16位值
     * @return 如果值有变化返回true，否则返回false
     */
    public boolean updateValue(short registerValue) {
        return super.updateValue(unsignedShortValue(registerValue));
    }

    /**
     * 从Modbus读取的单个寄存器更新属性值，并设置状态。
     *
     * @param registerValue 从Modbus寄存器读取的16位值
     * @param status 属性状态
     * @return 如果值有变化返回true，否则返回false
     */
    public boolean updateValue(short registerValue, AttributeStatus status) {
        return super.updateValue(unsignedShortValue(registerValue), status);
    }

    @Override
    public boolean updateValue(Integer value) {
        return super.updateValue(requireUnsignedShort(value));
    }

    @Override
    public boolean updateValue(Integer value, AttributeStatus newStatus) {
        return super.updateValue(requireUnsignedShort(value), newStatus);
    }

    @Override
    protected Integer convertFromUnitImp(Integer value, UnitInfo fromUnit) {
        if (value == null) return null;

        if (fromUnit == null || nativeUnit == null) {
            return requireUnsignedShort(value);
        }
        // 同单位族才可换算；跨单位族没有可证明的换算关系，显式失败而不是按原值猜测
        if (nativeUnit.getClass().equals(fromUnit.getClass())) {
            return convertToType(value.doubleValue() * fromUnit.convertUnit(nativeUnit));
        }
        throw new IllegalArgumentException("单位族不兼容，不能写入 Unsigned Short 属性: "
                + fromUnit.getClass().getName() + " -> " + nativeUnit.getClass().getName());
    }

    @Override
    protected Integer convertToType(double value) {
        // 单位换算可能产生小数；不能静默截断，否则设备会收到与请求不同的值
        if (!Double.isFinite(value) || value != Math.rint(value)) {
            throw new IllegalArgumentException("参数仅支持整数值，收到: " + value);
        }
        return requireUnsignedShort((int) value);
    }

    private static int unsignedShortValue(short registerValue) {
        return registerValue & MAX_UNSIGNED_SHORT;
    }

    private static Integer requireUnsignedShort(Integer value) {
        if (value == null || value < MIN_UNSIGNED_SHORT || value > MAX_UNSIGNED_SHORT) {
            throw new IllegalArgumentException(
                    "Unsigned Short 数据越界，允许范围 0..65535，收到: " + value);
        }
        return value;
    }
}
