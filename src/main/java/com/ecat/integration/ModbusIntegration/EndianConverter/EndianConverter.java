package com.ecat.integration.ModbusIntegration.EndianConverter;

/**
 * EndianConverter接口定义了端序转换器的基本操作
 * 
 * @author coffee
 */
public interface EndianConverter {
    /**
     * 将浮点值转换为端序相关的short数组（2个short）
     * @param value 浮点值
     * @return short数组（大端为[高位寄存器, 低位寄存器]，小端为[低位寄存器, 高位寄存器]）
     */
    short[] floatToShorts(float value);

    /**
     * 将两个short值转换为浮点值（根据端序组合字节）
     * @param word1 第一个short（可能是高位或低位寄存器，由端序决定）
     * @param word2 第二个short（可能是低位或高位寄存器，由端序决定）
     * @return 浮点值
     */
    float shortsToFloat(short word1, short word2);

    /**
     * 将整数值转换为端序相关的short数组（2个short）
     * @param value 整数值
     * @return short数组（大端为[高位寄存器, 低位寄存器]，小端为[低位寄存器, 高位寄存器]）
     */
    short[] intToShorts(int value);

    /**
     * 将两个short值转换为整数值（根据端序组合字节）
     * @param word1 第一个short（可能是高位或低位寄存器，由端序决定）
     * @param word2 第二个short（可能是低位或高位寄存器，由端序决定）
     * @return 整数值
     */
    int shortsToInt(short word1, short word2);

    /**
     * 将整数值转换为short（根据端序）
     * @param value 整数值,范围应在-32768到32767之间
     * @return short值
     */
    short intToShort(int value);

    /**
     * 将short值转换为整数值
     * @param value short值
     * @return 整数值
     */
    int shortToInt(short value);
}
