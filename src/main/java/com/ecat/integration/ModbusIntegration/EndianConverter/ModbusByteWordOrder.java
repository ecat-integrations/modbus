package com.ecat.integration.ModbusIntegration.EndianConverter;

/**
 * Modbus 32 位值(INT32/UINT32/FLOAT32,跨 2 寄存器)的字节序×字序统一枚举。
 *
 * <p>32 位值有 4 字节,记 A=最高位字节(MSB)..D=最低位字节(LSB)。Modbus 设备把这 4 字节
 * 拆进 2 个寄存器,字节在寄存器内的顺序(字节序)+ 寄存器在地址上的顺序(字序)共 4 种组合,
 * 业界用 ABCD/BADC/CDAB/DCBA 标记(字母 = 该值字节在线序上的排列):
 * <ul>
 *   <li><b>ABCD</b>:大字节序 + 高字在前(标准 Modbus,多数设备)。
 *       寄存器布局(地址低→高):reg0=[A,B], reg1=[C,D]。</li>
 *   <li><b>BADC</b>:字节交换(寄存器内小字节序)+ 高字在前。
 *       reg0=[B,A], reg1=[D,C]。部分国产/特殊设备。</li>
 *   <li><b>CDAB</b>:字交换(低字在前)+ 大字节序。<b>现场最常见"非标准"</b>
 *       ——Schneider Modicon、变频器、Danfoss、saimosen 气态分析仪等。reg0=[C,D], reg1=[A,B]。</li>
 *   <li><b>DCBA</b>:全反(小字节序 + 低字在前)。reg0=[D,C], reg1=[B,A]。</li>
 * </ul>
 *
 * <p>16 位单寄存器值(INT16/UINT16)Modbus 协议固定大字节序,不适用本枚举。
 *
 * <p><b>转换契约</b>:{@link EndianConverter#forOrder(ModbusByteWordOrder)} 返回的 converter,
 * 其 {@code shortsToInt/shortsToFloat} 接收 reader 传来的地址顺序两寄存器
 * {@code (firstReg=data[offset], secondReg=data[offset+1])},按本枚举对应的顺序还原值。
 *
 * <p><b>历史误标说明</b>:既有 {@code LittleEndianConverter} 名义"小端",实测解码的是 <b>CDAB</b>
 * (大字节+低字在前),不是 DCBA。本枚举用真实顺序命名,消除误导(见 EndianConverterSemanticsTest)。
 * 
 * @author coffee
 */
public enum ModbusByteWordOrder {
    /** 大字节序 + 高字在前(标准 Modbus)。 */
    ABCD("ABCD 大端(标准)"),
    /** 寄存器内字节交换(小字节序)+ 高字在前。 */
    BADC("BADC 字节交换"),
    /** 字交换:低字在前 + 大字节序(现场最常见"非标准")。 */
    CDAB("CDAB 字交换(常见)"),
    /** 全反:小字节序 + 低字在前。 */
    DCBA("DCBA 小端全反");

    private final String displayLabel;

    ModbusByteWordOrder(String displayLabel) {
        this.displayLabel = displayLabel;
    }

    /** UI 下拉显示文案(顺序标记 + 中文说明)。 */
    public String getDisplayLabel() {
        return displayLabel;
    }
}
