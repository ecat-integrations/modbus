package com.ecat.integration.ModbusIntegration.EndianConverter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 4 种字节序×字序(ABCD/BADC/CDAB/DCBA)统一解码的业务计算测试。
 *
 * <p>锚点:32 位值位模式 0x12345678(A=0x12 MSB,B=0x34,C=0x56,D=0x78 LSB)。
 * 每种顺序的设备会把这 4 字节按自己的布局拆进 2 个寄存器(地址低→高):
 * <ul>
 *   <li>ABCD:firstReg=0x1234([A,B]), secondReg=0x5678([C,D])</li>
 *   <li>BADC:firstReg=0x3412([B,A]), secondReg=0x7856([D,C])</li>
 *   <li>CDAB:firstReg=0x5678([C,D]), secondReg=0x1234([A,B])</li>
 *   <li>DCBA:firstReg=0x7856([D,C]), secondReg=0x3412([B,A])</li>
 * </ul>
 * 对每种顺序,用其布局的寄存器对喂给 {@code forOrder(order)} 返回的 converter,必须还原出位模式 0x12345678
 * (int 直接相等;float 用 {@link Float#floatToIntBits} 比位模式,不必算出浮点字面值)。
 *
 * <p>reader 契约:converter 的 {@code shortsToInt/shortsToFloat} 收到的永远是地址顺序
 * (firstReg=data[offset], secondReg=data[offset+1])。
 */
public class FourOrderEndianConverterTest {

    private static final int BITS = 0x12345678;
    private static final float FLOAT_ANCHOR = Float.intBitsToFloat(BITS);

    private void assertOrderRoundTripsInt(ModbusByteWordOrder order, short firstReg, short secondReg) {
        int decoded = EndianConverter.forOrder(order).shortsToInt(firstReg, secondReg);
        assertEquals(order + " int 解码应还原锚点位模式", BITS, decoded);
    }

    private void assertOrderRoundTripsFloat(ModbusByteWordOrder order, short firstReg, short secondReg) {
        float decoded = EndianConverter.forOrder(order).shortsToFloat(firstReg, secondReg);
        assertEquals(order + " float 解码应还原锚点位模式",
                BITS, Float.floatToIntBits(decoded));
        assertEquals(order + " float 值相等", FLOAT_ANCHOR, decoded, 0f);
    }

    @Test
    public void abcdDecodes() {
        assertOrderRoundTripsInt(ModbusByteWordOrder.ABCD, (short) 0x1234, (short) 0x5678);
        assertOrderRoundTripsFloat(ModbusByteWordOrder.ABCD, (short) 0x1234, (short) 0x5678);
    }

    @Test
    public void badcDecodes() {
        assertOrderRoundTripsInt(ModbusByteWordOrder.BADC, (short) 0x3412, (short) 0x7856);
        assertOrderRoundTripsFloat(ModbusByteWordOrder.BADC, (short) 0x3412, (short) 0x7856);
    }

    @Test
    public void cdabDecodes() {
        assertOrderRoundTripsInt(ModbusByteWordOrder.CDAB, (short) 0x5678, (short) 0x1234);
        assertOrderRoundTripsFloat(ModbusByteWordOrder.CDAB, (short) 0x5678, (short) 0x1234);
    }

    @Test
    public void dcbaDecodes() {
        assertOrderRoundTripsInt(ModbusByteWordOrder.DCBA, (short) 0x7856, (short) 0x3412);
        assertOrderRoundTripsFloat(ModbusByteWordOrder.DCBA, (short) 0x7856, (short) 0x3412);
    }

    /** 反证:对错布局必须解不出锚点(防 converter 退化成单一顺序)。 */
    @Test
    public void wrongLayoutDoesNotDecode() {
        // ABCD converter 喂 CDAB 布局 → 不还原
        int wrong = EndianConverter.forOrder(ModbusByteWordOrder.ABCD)
                .shortsToInt((short) 0x5678, (short) 0x1234);
        assertEquals("ABCD converter 喂 CDAB 布局应解错", 0x56781234, wrong);
    }

    /** 写入路径 encode 与读取 decode 互逆:int → 2 short → int 还原(锁 4 种顺序的 encode 正确性)。 */
    @Test
    public void intRoundTripAllOrders() {
        for (ModbusByteWordOrder order : ModbusByteWordOrder.values()) {
            EndianConverter c = EndianConverter.forOrder(order);
            short[] regs = c.intToShorts(BITS);
            assertEquals(order + ": encode 后应得 2 个寄存器", 2, regs.length);
            int back = c.shortsToInt(regs[0], regs[1]);
            assertEquals(order + ": int encode→decode 应还原锚点", BITS, back);
        }
    }

    /** 写入路径 encode 与读取 decode 互逆:float → 2 short → float 还原。 */
    @Test
    public void floatRoundTripAllOrders() {
        for (ModbusByteWordOrder order : ModbusByteWordOrder.values()) {
            EndianConverter c = EndianConverter.forOrder(order);
            short[] regs = c.floatToShorts(FLOAT_ANCHOR);
            float back = c.shortsToFloat(regs[0], regs[1]);
            assertEquals(order + ": float encode→decode 应还原锚点位模式",
                    BITS, Float.floatToIntBits(back));
        }
    }

    /** forOrder 工厂:4 个 enum 值各返回非 null 且类型正确(ABCD/CDAB 复用现有,BADC/DCBA 新建)。 */
    @Test
    public void forOrderReturnsConverterForEachValue() {
        for (ModbusByteWordOrder order : ModbusByteWordOrder.values()) {
            assertEquals(order + " 应返回非 null converter", true,
                    EndianConverter.forOrder(order) != null);
        }
    }
}
