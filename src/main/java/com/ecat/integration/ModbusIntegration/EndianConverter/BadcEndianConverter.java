package com.ecat.integration.ModbusIntegration.EndianConverter;

import com.ecat.integration.ModbusIntegration.Tools;

/**
 * BADC 顺序转换器:寄存器内字节交换(小字节序)+ 高字在前。
 *
 * <p>32 位值字节 A(MSB)..D(LSB)的寄存器布局(地址低→高):firstReg=[B,A], secondReg=[D,C]。
 * 解码契约:reader 传地址顺序 {@code (firstReg=data[offset], secondReg=data[offset+1])}。
 *
 * <p><b>不封装 Tools 的 byte-swap 方法</b>:这些方法语义经特征化测试证实与命名不符,故此处用从字节标号
 * 推导的干净公式,避免继承歧义(见 {@code EndianConverterSemanticsTest})。单寄存器(16 位)的
 * intToShort/shortToInt 复用 Tools 的小端变体——单字场景下 BADC 与 DCBA 都是小字节序,等价。
 * 
 * @author coffee
 */
public class BadcEndianConverter extends AbstractEndianConverter {

    /** BADC 解码:(firstReg=[B,A], secondReg=[D,C]) → A<<24|B<<16|C<<8|D。 */
    @Override
    public int shortsToInt(short firstReg, short secondReg) {
        int a = firstReg & 0xFF;
        int b = (firstReg >> 8) & 0xFF;
        int c = secondReg & 0xFF;
        int d = (secondReg >> 8) & 0xFF;
        return (a << 24) | (b << 16) | (c << 8) | d;
    }

    /** BADC 编码:value → firstReg=[B,A], secondReg=[D,C]。 */
    @Override
    public short[] intToShorts(int value) {
        int a = (value >> 24) & 0xFF;
        int b = (value >> 16) & 0xFF;
        int c = (value >> 8) & 0xFF;
        int d = value & 0xFF;
        // firstReg=[B,A], secondReg=[D,C]
        return new short[]{ (short) ((b << 8) | a), (short) ((d << 8) | c) };
    }

    @Override
    public float shortsToFloat(short firstReg, short secondReg) {
        return Float.intBitsToFloat(shortsToInt(firstReg, secondReg));
    }

    @Override
    public short[] floatToShorts(float value) {
        return intToShorts(Float.floatToIntBits(value));
    }

    /** 单寄存器(16 位)BADC = 寄存器内小字节序 → 复用 Tools 小端变体。 */
    @Override
    public short intToShort(int value) {
        return Tools.convertIntToShortLittleEndian(value);
    }

    @Override
    public int shortToInt(short value) {
        return Tools.convertShortToIntLittleEndian(value);
    }
}
