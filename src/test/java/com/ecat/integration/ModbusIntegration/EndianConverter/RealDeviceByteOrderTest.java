package com.ecat.integration.ModbusIntegration.EndianConverter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 用**真实设备抓包/手册数据**验证 4 种字节序×字序 converter 的解码正确性(非合成值)。
 *
 * <p>每个数据点来自一个真实 Modbus 集成的单测:两个寄存器 short 值(地址低→高,即 reader 传给 converter 的
 * {@code (firstReg=data[offset], secondReg=data[offset+1])}) + 设备手册/抓包标注的工程值。
 * 用 {@link EndianConverter#forOrder(ModbusByteWordOrder)} 取该设备所属顺序的 converter,断言解出工程值。
 *
 * <p>顺序归属由"真实工程值反推"确定(对每个寄存器对算 4 种顺序,只有一种得到合理工程值)——不依赖厂商代码里
 * 语义混乱的方法名(convertLittleEndianToFloat 实测=CDAB 等,见 {@link EndianConverterSemanticsTest})。
 *
 * <p><b>覆盖现状</b>:ABCD(tyxdgroup)、BADC(saimosen)、CDAB(xian-lechi/tianhong)均有真实设备数据;
 * <b>DCBA 在现有 6 个 Modbus 厂商集成中无真实设备</b>(environnement-sa 用的方法实测也是 CDAB),
 * 故 DCBA 的数值正确性由 {@link FourOrderEndianConverterTest} 的 0x12345678 解码 + encode↔decode round-trip 覆盖。
 */
public class RealDeviceByteOrderTest {

    private void assertFloat(ModbusByteWordOrder order, short firstReg, short secondReg,
                             float expected, String device) {
        float got = EndianConverter.forOrder(order).shortsToFloat(firstReg, secondReg);
        assertEquals(device + " (" + order + ") 应解出手册/抓包工程值",
                expected, got, 0.01f);
    }

    /** tyxdgroup SO2:协议文档例程 4.2.1,地址 0x0079 两寄存器 [0x42B6,0xBC45] → 91.37 ppb(ABCD)。 */
    @Test
    public void tyxdgroupSo2IsAbcd() {
        assertFloat(ModbusByteWordOrder.ABCD, (short) 0x42B6, (short) 0xBC45, 91.37f,
                "tyxdgroup SO2 91.37ppb");
    }

    /**
     * saimosen O3:真实抓包帧第一个 float,寄存器 [0xBF3E,0xFB7C] → 0.374(BADC)。
     * <p>注:saimosen 代码调 convertLittleEndianByteSwapToFloat,名义常被标"CDAB",但实测反推(0.374 工程值)
     * 只有 BADC 顺序能解出——寄存器内字节交换 + 高字在前。
     */
    @Test
    public void saimosenO3IsBadc() {
        assertFloat(ModbusByteWordOrder.BADC, (short) 0xBF3E, (short) 0xFB7C, 0.374f,
                "saimosen O3 0.374");
    }

    /** xian-lechi LC-700 风速:task-weather.md 真实抓包,[0xCCCD,0x3F4C] → 0.80 m/s(CDAB/字交换)。 */
    @Test
    public void xianLechiWindSpeedIsCdab() {
        assertFloat(ModbusByteWordOrder.CDAB, (short) 0xCCCD, (short) 0x3F4C, 0.80f,
                "xian-lechi LC-700 风速 0.80m/s");
    }

    /** xian-lechi LC-700 温度:同一抓包,[0x999A,0x41BB] → 23.45 ℃(CDAB)。 */
    @Test
    public void xianLechiTemperatureIsCdab() {
        assertFloat(ModbusByteWordOrder.CDAB, (short) 0x999A, (short) 0x41BB, 23.45f,
                "xian-lechi LC-700 温度 23.45℃");
    }

    /** tianhong TH-2001H NO2:真实串口抓包,数据区 [0xE96C,0x40DC] → ≈6.9 ppb(CDAB/低字在前)。 */
    @Test
    public void tianhongNo2IsCdab() {
        assertFloat(ModbusByteWordOrder.CDAB, (short) 0xE96C, (short) 0x40DC, 6.9f,
                "tianhong TH-2001H NO2 ~6.9ppb");
    }

    /**
     * 反证:同一 saimosen O3 寄存器对,若误用 CDAB 顺序会解出离谱值(证明顺序选错 = 数据全错,
     * 也佐证 BADC 归属唯一)。
     */
    @Test
    public void wrongOrderGarblesRealData() {
        float cdabDecode = EndianConverter.forOrder(ModbusByteWordOrder.CDAB)
                .shortsToFloat((short) 0xBF3E, (short) 0xFB7C);
        // CDAB 解这对寄存器得到的天文数字,与真实 0.374 完全不符
        assertEquals("saimosen O3 误用 CDAB 会解出错误值", true, Math.abs(cdabDecode) > 1e10f);
    }
}
