package com.ecat.integration.ModbusIntegration.EndianConverter;

import com.ecat.integration.ModbusIntegration.Tools;

/**
 * 小端序转换器类
 * 
 * @author coffee
 */
public class LittleEndianConverter extends AbstractEndianConverter {
    @Override
    public short[] floatToShorts(float value) {
        return Tools.convertFloatToLittleEndianShorts(value);
    }

    @Override
    public float shortsToFloat(short word1, short word2) {
        // 注意：Tools中convertLittleEndianToFloat参数顺序是(lowWord, highWord)
        // 因此这里需要将传入的word1（低位寄存器）和word2（高位寄存器）按顺序传递
        return Tools.convertLittleEndianToFloat(word1, word2);
    }
    
    @Override
    public short[] intToShorts(int value) {
        return Tools.convertIntToLittleEndianShorts(value);
    }

    @Override
    public int shortsToInt(short word1, short word2) {
        // 小端模式：word1是低位寄存器，word2是高位寄存器
        return Tools.convertLittleEndianToInt(word1, word2);
    }

    @Override
    public short intToShort(int value) {
        return Tools.convertIntToShortLittleEndian(value);
    }

    @Override
    public int shortToInt(short value) {
        return Tools.convertShortToIntLittleEndian(value);
    }
}
