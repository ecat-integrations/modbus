package com.ecat.integration.ModbusIntegration.EndianConverter;

import com.ecat.integration.ModbusIntegration.Tools;

/**
 * DCBA 顺序转换器:全反(小字节序 + 低字在前)。
 *
 * <p>32 位值字节 A(MSB)..D(LSB)的寄存器布局(地址低→高):firstReg=[D,C], secondReg=[B,A]。
 * 解码契约:reader 传地址顺序 {@code (firstReg=data[offset], secondReg=data[offset+1])}。
 *
 * <p>用从字节标号推导的干净公式(不封装语义混乱的 Tools byte-swap 方法,见
 * {@code EndianConverterSemanticsTest})。单寄存器(16 位)intToShort/shortToInt 复用 Tools 小端变体
 * (单字场景下 DCBA 与 BADC 都是小字节序)。
 * 
 * @author coffee
 */
public class DcbaEndianConverter extends AbstractEndianConverter {

    /** DCBA 解码:(firstReg=[D,C], secondReg=[B,A]) → A<<24|B<<16|C<<8|D。 */
    @Override
    public int shortsToInt(short firstReg, short secondReg) {
        int c = firstReg & 0xFF;
        int d = (firstReg >> 8) & 0xFF;
        int a = secondReg & 0xFF;
        int b = (secondReg >> 8) & 0xFF;
        return (a << 24) | (b << 16) | (c << 8) | d;
    }

    /** DCBA 编码:value → firstReg=[D,C], secondReg=[B,A]。 */
    @Override
    public short[] intToShorts(int value) {
        int a = (value >> 24) & 0xFF;
        int b = (value >> 16) & 0xFF;
        int c = (value >> 8) & 0xFF;
        int d = value & 0xFF;
        // firstReg=[D,C], secondReg=[B,A]
        return new short[]{ (short) ((d << 8) | c), (short) ((b << 8) | a) };
    }

    @Override
    public float shortsToFloat(short firstReg, short secondReg) {
        return Float.intBitsToFloat(shortsToInt(firstReg, secondReg));
    }

    @Override
    public short[] floatToShorts(float value) {
        return intToShorts(Float.floatToIntBits(value));
    }

    /** 单寄存器(16 位)DCBA = 寄存器内小字节序 → 复用 Tools 小端变体。 */
    @Override
    public short intToShort(int value) {
        return Tools.convertIntToShortLittleEndian(value);
    }

    @Override
    public int shortToInt(short value) {
        return Tools.convertShortToIntLittleEndian(value);
    }
}
