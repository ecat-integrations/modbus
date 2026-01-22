package com.ecat.integration.ModbusIntegration.EndianConverter;

import com.ecat.integration.ModbusIntegration.Tools;

/**
 * 大端序转换器类
 * 
 * @author coffee
 */
public class BigEndianConverter extends AbstractEndianConverter {
    @Override
    public short[] floatToShorts(float value) {
        return Tools.convertFloatToBigEndianShorts(value);
    }

    @Override
    public float shortsToFloat(short word1, short word2) {
        // 对应Tools的convertBigEndianToFloat参数顺序（word1=高位寄存器，word2=低位寄存器）
        return Tools.convertBigEndianToFloat(word1, word2);
    }

    @Override
    public short[] intToShorts(int value) {
        return Tools.convertIntToBigEndianShorts(value);
    }

    @Override
    public int shortsToInt(short word1, short word2) {
        // 大端模式：word1是高位寄存器，word2是低位寄存器
        return Tools.convertBigEndianToInt(word1, word2);
    }

    @Override
    public short intToShort(int value) {
        return Tools.convertIntToShortBigEndian(value);
    }

    @Override
    public int shortToInt(short value) {
        return Tools.convertShortToIntBigEndian(value);
    }
}
