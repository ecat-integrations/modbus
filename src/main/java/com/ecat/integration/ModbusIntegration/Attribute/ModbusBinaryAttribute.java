package com.ecat.integration.ModbusIntegration.Attribute;

import java.util.concurrent.CompletableFuture;

import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.BinaryAttribute;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.ModbusTransactionStrategy;

/**
 * ModbusBinaryAttribute class
 *
 * This class represents a Modbus attribute that can be read and written as a binary value.
 * It extends the BinaryAttribute class and provides methods to manage the binary attribute
 * using Modbus coil operations.
 *
 * This class is suitable for attributes that represent binary states such as
 * relay switches, digital outputs, etc.
 *
 * @Author coffee
 */
public class ModbusBinaryAttribute extends BinaryAttribute {

    private ModbusSource modbusSource; // Modbus源
    private int coilAddress; // 目标线圈地址

    /**
     * 构造函数：使用i18n显示displayName
     *
     * @param attributeID
     * @param attrClass
     * @param valueChangeable
     * @param modbusSource
     * @param coilAddress
     */
    public ModbusBinaryAttribute(String attributeID, AttributeClass attrClass,
            boolean valueChangeable, ModbusSource modbusSource, int coilAddress) {
        super(attributeID, attrClass, valueChangeable);
        this.modbusSource = modbusSource;
        this.coilAddress = coilAddress;
    }

    /**
     * 同时支持用户设置显示名称displayName和I18n的构造函数
     * 适合参数名称不固定的设备的国际化支持，比如数据采集转换模块的不同通道需要用户设置名称，以用户语言文字设置的显示名称为高优先级
     *
     * @param attributeID
     * @param displayName 用户设置的显示名称，优先级高
     * @param attrClass
     * @param valueChangeable
     * @param modbusSource
     * @param coilAddress
     */
    public ModbusBinaryAttribute(String attributeID, String displayName, AttributeClass attrClass,
            boolean valueChangeable, ModbusSource modbusSource, int coilAddress) {
        super(attributeID, displayName, attrClass, valueChangeable);
        this.modbusSource = modbusSource;
        this.coilAddress = coilAddress;
    }

    @Override
    protected CompletableFuture<Boolean> asyncTurnOnImpl() {
        boolean newValue = true;
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
            return source.writeCoil(coilAddress, newValue)
                    .thenCompose((response) -> {
                        if (response == null || response.isException()) {
                            throw new RuntimeException("命令下发失败: " + response.getExceptionMessage());
                        }
                        return CompletableFuture.completedFuture(true);
                    });
        });
    }

    @Override
    protected CompletableFuture<Boolean> asyncTurnOffImpl() {
        boolean newValue = false;
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
            return source.writeCoil(coilAddress, newValue)
                    .thenCompose((response) -> {
                        if (response == null || response.isException()) {
                            throw new RuntimeException("命令下发失败: " + response.getExceptionMessage());
                        }
                        return CompletableFuture.completedFuture(true);
                    });
        });
    }

    /**
     * 从Modbus读取的线圈状态更新属性值
     */
    public boolean updateValue(boolean coilValue) {
        return super.updateValue(coilValue);
    }

    @Override
    public boolean updateValue(Boolean value) {
        return super.updateValue(value);
    }
}
