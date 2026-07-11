package com.ecat.integration.ModbusIntegration.EndianConverter;

import org.junit.Test;
import com.ecat.integration.ModbusIntegration.Tools;

import static org.junit.Assert.assertEquals;

/**
 * 现有 BigEndianConverter / LittleEndianConverter 的真实语义特征化测试。
 *
 * <p>目的:用 32 位锚点值 0x12345678(字节 A=0x12 MSB, B=0x34, C=0x56, D=0x78 LSB)钉死
 * 每个 converter 在"reader 总传地址顺序两寄存器 (firstReg=data[offset], secondReg=data[offset+1])"
 * 契约下,到底解码的是 4 种顺序(ABCD/BADC/CDAB/DCBA)中的哪一种。
 *
 * <p>4 种顺序的寄存器布局(地址低→高),对同一值 0x12345678:
 * <ul>
 *   <li>ABCD:firstReg=[A,B]=0x1234, secondReg=[C,D]=0x5678(大字节+高字在前)</li>
 *   <li>BADC:firstReg=[B,A]=0x3412, secondReg=[D,C]=0x7856(小字节+高字在前)</li>
 *   <li>CDAB:firstReg=[C,D]=0x5678, secondReg=[A,B]=0x1234(大字节+低字在前/字交换)</li>
 *   <li>DCBA:firstReg=[D,C]=0x7856, secondReg=[B,A]=0x3412(小字节+低字在前)</li>
 * </ul>
 * 每种布局的"正确解码"= 给该布局的寄存器对,还原出 0x12345678。
 */
public class EndianConverterSemanticsTest {

    private static final int ANCHOR = 0x12345678;

    /** BigEndianConverter 解码 ABCD 布局(高字在前+大字节)。 */
    @Test
    public void bigEndianDecodesAbcd() {
        BigEndianConverter c = new BigEndianConverter();
        assertEquals(ANCHOR, c.shortsToInt((short) 0x1234, (short) 0x5678));
    }

    /**
     * LittleEndianConverter 实际解码的是 CDAB(低字在前+大字节),名义"小端"是误标。
     * firstReg 当 lowWord、secondReg 当 highWord → 给 CDAB 布局 [0x5678,0x1234] 还原锚点。
     */
    @Test
    public void littleEndianDecodesCdabNotDcba() {
        LittleEndianConverter c = new LittleEndianConverter();
        assertEquals(ANCHOR, c.shortsToInt((short) 0x5678, (short) 0x1234));
    }

    /** 反证:BigEndianConverter 给 BADC 布局 [0x3412,0x7856] 不还原锚点(得 0x34127856)。 */
    @Test
    public void bigEndianDoesNotDecodeBadc() {
        BigEndianConverter c = new BigEndianConverter();
        assertEquals(0x34127856, c.shortsToInt((short) 0x3412, (short) 0x7856));
    }

    /** 反证:LittleEndianConverter 给 BADC 布局 [0x3412,0x7856] 不还原锚点(得 0x78563412)。 */
    @Test
    public void littleEndianDoesNotDecodeBadc() {
        LittleEndianConverter c = new LittleEndianConverter();
        assertEquals(0x78563412, c.shortsToInt((short) 0x3412, (short) 0x7856));
    }

    /**
     * 锁定 4 个 Tools float 静态方法的真实 ABCD 标签( caller 按方法 signature 自然传地址序两寄存器):
     * convertBigEndianToFloat=ABCD、convertLittleEndianToFloat=CDAB、convertBigEndianByteSwapToFloat=BADC、
     * convertLittleEndianByteSwapToFloat=DCBA。用于纠正 Tools javadoc 里历史上互混的字母标注。
     */
    @Test
    public void toolsFloatMethodsActualOrderLabels() {
        int anchor = 0x12345678;
        int ab = Float.floatToIntBits(Tools.convertBigEndianToFloat((short) 0x1234, (short) 0x5678));
        int cd = Float.floatToIntBits(Tools.convertLittleEndianToFloat((short) 0x5678, (short) 0x1234));
        int badc = Float.floatToIntBits(Tools.convertBigEndianByteSwapToFloat((short) 0x3412, (short) 0x7856));
        int dcba = Float.floatToIntBits(Tools.convertLittleEndianByteSwapToFloat((short) 0x7856, (short) 0x3412));
        assertEquals("convertBigEndianToFloat = ABCD", anchor, ab);
        assertEquals("convertLittleEndianToFloat = CDAB(非 javadoc 旧标的 DCBA)", anchor, cd);
        assertEquals("convertBigEndianByteSwapToFloat = BADC", anchor, badc);
        assertEquals("convertLittleEndianByteSwapToFloat = DCBA(非 javadoc 旧标的 CDAB)", anchor, dcba);
    }
}
