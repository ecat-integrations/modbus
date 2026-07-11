package com.ecat.integration.ModbusIntegration.EndianConverter;

import com.ecat.integration.ModbusIntegration.Tools;

/**
 * CDAB 字序转换器(低字在前 + 字内大字节序)。封装 {@link Tools#convertLittleEndianToFloat} 等。
 * <p>类名"Little"承自历史命名,指<b>字序</b>低字在前(非字节序小端 DCBA)——实测锁定为 CDAB,
 * 见 {@code EndianConverterSemanticsTest#littleEndianDecodesCdabNotDcba}。
 * {@link ModbusByteWordOrder#CDAB} 经 {@link EndianConverter#forOrder} 复用本类。
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
