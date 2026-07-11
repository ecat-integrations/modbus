package com.ecat.integration.ModbusIntegration;

/**
 * Tools class for Modbus TCP Integration
 * 
 * This class provides utility methods for converting between different byte orders
 * and handling Modbus transactions.
 * 
 * @author coffee
 */
public class Tools {

    /**
     * 大端模式浮点数转换（高位寄存器在前，高位字节在前） （ A B C D ）
     * @param highWord 高位寄存器值（如0x201）
     * @param lowWord  低位寄存器值（如0x200）
     * @return float值
     */
    public static float convertBigEndianToFloat(short highWord, short lowWord) {
        // 大端模式：[highWord高字节, highWord低字节, lowWord高字节, lowWord低字节]
        byte b0 = (byte) ((highWord >> 8) & 0xFF);  // 高位寄存器高字节（最高位）
        byte b1 = (byte) (highWord & 0xFF);         // 高位寄存器低字节
        byte b2 = (byte) ((lowWord >> 8) & 0xFF);   // 低位寄存器高字节
        byte b3 = (byte) (lowWord & 0xFF);          // 低位寄存器低字节（最低位）

        int intValue = 
            ((b0 & 0xFF) << 24) | 
            ((b1 & 0xFF) << 16) | 
            ((b2 & 0xFF) << 8) | 
            (b3 & 0xFF);
        return Float.intBitsToFloat(intValue);
    }

    /**
     * 浮点数转换 = CDAB 字序(低字在前 + 字内大字节序)。
     * <p>caller 按地址序传 (firstReg=data[offset], secondReg=data[offset+1]),本方法把 firstReg 当低字、
     * secondReg 当高字,字内按大字节序还原——即 CDAB(低字在前)。旧注释"小端/DCBA/低位字节在前"是误导:
     * 实测锁定见 {@link EndianConverterSemanticsTest#toolsFloatMethodsActualOrderLabels}(非 DCBA 全反)。
     * @param lowWord  低位寄存器值(地址低的那个,如0x200)
     * @param highWord 高位寄存器值(地址高的那个,如0x201)
     * @return float值
     */
    public static float convertLittleEndianToFloat(short lowWord, short highWord) {
        // CDAB:低字(lowWord=firstReg)在前,字内大字节序还原
        byte b0 = (byte) (lowWord & 0xFF);          // 低位寄存器低字节（最低位）
        byte b1 = (byte) ((lowWord >> 8) & 0xFF);   // 低位寄存器高字节
        byte b2 = (byte) (highWord & 0xFF);         // 高位寄存器低字节
        byte b3 = (byte) ((highWord >> 8) & 0xFF);  // 高位寄存器高字节（最高位）

        int intValue = 
            ((b0 & 0xFF) << 0) |   // 最低位（左移0位）
            ((b1 & 0xFF) << 8) |   // 次低位（左移8位）
            ((b2 & 0xFF) << 16) |  // 次高位（左移16位）
            ((b3 & 0xFF) << 24);   // 最高位（左移24位）
        return Float.intBitsToFloat(intValue);
    }

    /**
     * 大端模式字节交换浮点数转换（ B A D C ）
     * @param highWord 高位寄存器值（如0x201）
     * @param lowWord  低位寄存器值（如0x200）
     * @return float值
     */
    public static float convertBigEndianByteSwapToFloat(short highWord, short lowWord) {
        // 大端字节交换模式：[highWord低字节, highWord高字节, lowWord低字节, lowWord高字节]
        byte b0 = (byte) (highWord & 0xFF);         // 高位寄存器低字节（最高位）
        byte b1 = (byte) ((highWord >> 8) & 0xFF);  // 高位寄存器高字节
        byte b2 = (byte) (lowWord & 0xFF);          // 低位寄存器低字节
        byte b3 = (byte) ((lowWord >> 8) & 0xFF);   // 低位寄存器高字节（最低位）

        int intValue =
            ((b0 & 0xFF) << 24) |
            ((b1 & 0xFF) << 16) |
            ((b2 & 0xFF) << 8) |
            (b3 & 0xFF);
        return Float.intBitsToFloat(intValue);
    }

    /**
     * 浮点数转换 = DCBA 字序(低字在前 + 字内小字节序)。caller 按地址序传(firstReg, secondReg) 为 (low, high)。
     * <p>旧注释标的 "CDAB" 是错的——实测锁定见 {@link EndianConverterSemanticsTest#toolsFloatMethodsActualOrderLabels}(实际 DCBA)。
     * <p><b>saimosen 气态设备</b>(SO2/CO/NO2/O3)反转参数调此法(传 secondReg, firstReg),净效果 = BADC
     * (高字在前 + 字内小字节序),见 {@code RealDeviceByteOrderTest#saimosenO3IsBadc}(O3 [0xBF3E,0xFB7C]→0.374 实测)。
     * @param lowWord  低位寄存器值(地址低的那个,如0x200)
     * @param highWord 高位寄存器值(地址高的那个,如0x201)
     * @return float值
     */
    public static float convertLittleEndianByteSwapToFloat(short lowWord, short highWord) {
        // DCBA:低字在前 + 字内小字节序(寄存器内字节交换)
        byte b0 = (byte) ((lowWord >> 8) & 0xFF);   // 低位寄存器高字节（最低位）
        byte b1 = (byte) (lowWord & 0xFF);          // 低位寄存器低字节
        byte b2 = (byte) ((highWord >> 8) & 0xFF);  // 高位寄存器高字节
        byte b3 = (byte) (highWord & 0xFF);         // 高位寄存器低字节（最高位）

        int intValue =
            ((b0 & 0xFF) << 0) |   // 最低位（左移0位）
            ((b1 & 0xFF) << 8) |   // 次低位（左移8位）
            ((b2 & 0xFF) << 16) |  // 次高位（左移16位）
            ((b3 & 0xFF) << 24);   // 最高位（左移24位）
        return Float.intBitsToFloat(intValue);
    }

    /**
     * 大端序转小端序（按16位字交换）再转float
     * 输入：大端序的两个16位寄存器值（高位在前）
     * 输出：转换后的float值
     *
     * 示例：
     * 输入：highWord=0xFC0E，lowWord=0x3E3A（对应大端序 FC0E3E3A）
     * 输出：0.18 m/s
     * 
     * @deprecated 推荐使用 convertLittleEndianToFloat 方法更符合标准，相同高低字节R0\R1输入函数一致的参数顺序(R0,R1)后两个方法输出相同
     */
    public static float convertBigEndianToLittleEndianFloat(short highWord, short lowWord) {
        // 步骤1：FC0E 3E3A 按16位字交换，得到 3E3A FC0E
        byte b0 = (byte) ((lowWord >> 8) & 0xFF);  // 低字的高字节 3E
        byte b1 = (byte) (lowWord & 0xFF);         // 低字的低字节 3A
        byte b2 = (byte) ((highWord >> 8) & 0xFF); // 高字的高字节 FC
        byte b3 = (byte) (highWord & 0xFF);        // 高字的低字节 0E

        // 步骤2：组合为小端序整数 3E3AFC0E
        int intValue =
            ((b0 & 0xFF) << 24) |   // 3E << 24
            ((b1 & 0xFF) << 16) |   // 3A << 16
            ((b2 & 0xFF) << 8) |    // FC << 8
            (b3 & 0xFF);            // 0E

        // 步骤3：转float
        return Float.intBitsToFloat(intValue);
    }

    /**
     * 大端模式浮点数逆转换（将float转换为大端模式的short数组）
     * @param value 需要转换的float值
     * @return short数组[高位寄存器, 低位寄存器]
     */
    public static short[] convertFloatToBigEndianShorts(float value) {
        int intBits = Float.floatToIntBits(value);
        
        // 分解4字节为大端顺序的四个byte（b0最高位，b3最低位）
        byte b0 = (byte) ((intBits >> 24) & 0xFF);  // 最高位字节
        byte b1 = (byte) ((intBits >> 16) & 0xFF);
        byte b2 = (byte) ((intBits >> 8) & 0xFF);
        byte b3 = (byte) (intBits & 0xFF);          // 最低位字节

        // 重组为高位寄存器和低位寄存器（大端模式）
        short highWord = (short) ((b0 << 8) | (b1 & 0xFF));  // 高字节在前
        short lowWord = (short) ((b2 << 8) | (b3 & 0xFF));   // 低字节在后
        
        return new short[]{highWord, lowWord};
    }

    /**
     * 小端模式浮点数逆转换（将float转换为小端模式的short数组）
     * @param value 需要转换的float值
     * @return short数组[低位寄存器, 高位寄存器]
     */
    public static short[] convertFloatToLittleEndianShorts(float value) {
        int intBits = Float.floatToIntBits(value);
        
        // 分解4字节为小端顺序的四个byte（b0最低位，b3最高位）
        byte b0 = (byte) (intBits & 0xFF);          // 最低位字节
        byte b1 = (byte) ((intBits >> 8) & 0xFF);
        byte b2 = (byte) ((intBits >> 16) & 0xFF);
        byte b3 = (byte) ((intBits >> 24) & 0xFF);  // 最高位字节

        // 重组为低位寄存器和高位寄存器（小端模式）
        short lowWord = (short) ((b0 & 0xFF) | (b1 << 8));   // 低字节在前
        short highWord = (short) ((b2 & 0xFF) | (b3 << 8));  // 高字节在后
        
        return new short[]{lowWord, highWord};
    }

    /**
     * 大端模式整数转换（高位寄存器在前，高位字节在前）
     * @param highWord 高位寄存器值
     * @param lowWord  低位寄存器值
     * @return int值
     */
    public static int convertBigEndianToInt(short highWord, short lowWord) {
        // 大端模式：[highWord高字节, highWord低字节, lowWord高字节, lowWord低字节]
        byte b0 = (byte) ((highWord >> 8) & 0xFF);  // 高位寄存器高字节（最高位）
        byte b1 = (byte) (highWord & 0xFF);         // 高位寄存器低字节
        byte b2 = (byte) ((lowWord >> 8) & 0xFF);   // 低位寄存器高字节
        byte b3 = (byte) (lowWord & 0xFF);          // 低位寄存器低字节（最低位）

        int intValue = 
            ((b0 & 0xFF) << 24) | 
            ((b1 & 0xFF) << 16) | 
            ((b2 & 0xFF) << 8) | 
            (b3 & 0xFF);
        return intValue;
    }

    /**
     * 整数转换 = CDAB 字序(低字在前 + 字内大字节序)。同 {@link #convertLittleEndianToFloat} 的 int 版,
     * 旧注释"小端/低位字节在前"误导(实测=CDAB 非 DCBA,见 EndianConverterSemanticsTest)。
     * @param lowWord  低位寄存器值(地址低的那个)
     * @param highWord 高位寄存器值(地址高的那个)
     * @return int值
     */
    public static int convertLittleEndianToInt(short lowWord, short highWord) {
        // CDAB:低字(lowWord=firstReg)在前,字内大字节序还原
        byte b0 = (byte) (lowWord & 0xFF);          // 低位寄存器低字节（最低位）
        byte b1 = (byte) ((lowWord >> 8) & 0xFF);   // 低位寄存器高字节
        byte b2 = (byte) (highWord & 0xFF);         // 高位寄存器低字节
        byte b3 = (byte) ((highWord >> 8) & 0xFF);  // 高位寄存器高字节（最高位）

        int intValue = 
            ((b0 & 0xFF) << 0) |   // 最低位（左移0位）
            ((b1 & 0xFF) << 8) |   // 次低位（左移8位）
            ((b2 & 0xFF) << 16) |  // 次高位（左移16位）
            ((b3 & 0xFF) << 24);   // 最高位（左移24位）
        return intValue;
    }

    /**
     * 大端模式整数逆转换（将int转换为大端模式的short数组）
     * @param value 需要转换的int值
     * @return short数组[高位寄存器, 低位寄存器]
     */
    public static short[] convertIntToBigEndianShorts(int value) {
        // 分解32位整数为四个byte（大端顺序）
        byte b0 = (byte) ((value >> 24) & 0xFF);  // 最高位字节
        byte b1 = (byte) ((value >> 16) & 0xFF);
        byte b2 = (byte) ((value >> 8) & 0xFF);
        byte b3 = (byte) (value & 0xFF);          // 最低位字节

        // 重组为高位寄存器和低位寄存器（大端模式）
        short highWord = (short) ((b0 << 8) | (b1 & 0xFF));  // 高字节在前
        short lowWord = (short) ((b2 << 8) | (b3 & 0xFF));   // 低字节在后
        
        return new short[]{highWord, lowWord};
    }

    /**
     * 小端模式整数逆转换（将int转换为小端模式的short数组）
     * @param value 需要转换的int值
     * @return short数组[低位寄存器, 高位寄存器]
     */
    public static short[] convertIntToLittleEndianShorts(int value) {
        // 分解32位整数为四个byte（小端顺序）
        byte b0 = (byte) (value & 0xFF);          // 最低位字节
        byte b1 = (byte) ((value >> 8) & 0xFF);
        byte b2 = (byte) ((value >> 16) & 0xFF);
        byte b3 = (byte) ((value >> 24) & 0xFF);  // 最高位字节

        // 重组为低位寄存器和高位寄存器（小端模式）
        short lowWord = (short) ((b0 & 0xFF) | (b1 << 8));   // 低字节在前
        short highWord = (short) ((b2 & 0xFF) | (b3 << 8));  // 高字节在后
        
        return new short[]{lowWord, highWord};
    }

    /**
     * 将Short转换为int（大端模式）, 有符号
     * @param shortValue 需要转换的Short值
     * @return 转换后的int值
     */
    public static int convertShortToIntBigEndian(short shortValue) {
        // 大端模式：高字节在前，低字节在后
        byte highByte = (byte) ((shortValue >> 8) & 0xFF);  // 高字节
        byte lowByte = (byte) (shortValue & 0xFF);          // 低字节
        
        // 组合为int（保留符号位）
        return (highByte << 8) | (lowByte & 0xFF);
    }

    /**
     * 将int转换为Short（大端模式）, 有符号
     * @param intValue 需要转换的int值
     * @return 转换后的Short值
     * @throws ArithmeticException 当intValue超出Short范围时抛出
     */
    public static short convertIntToShortBigEndian(int intValue) {
        // 检查是否超出Short范围
        if (intValue < Short.MIN_VALUE || intValue > Short.MAX_VALUE) {
            throw new ArithmeticException("int值超出Short范围，无法转换：" + intValue);
        }
        
        // 大端模式：高字节在前，低字节在后
        byte highByte = (byte) ((intValue >> 8) & 0xFF);  // 高字节
        byte lowByte = (byte) (intValue & 0xFF);          // 低字节
        
        // 组合为short
        return (short) ((highByte << 8) | (lowByte & 0xFF));
    }

    /**
     * 将Short转换为int（小端模式）, 有符号
     * @param shortValue 需要转换的Short值
     * @return 转换后的int值
     */
    public static int convertShortToIntLittleEndian(short shortValue) {
        // 小端模式：低字节在前，高字节在后
        byte lowByte = (byte) (shortValue & 0xFF);          // 低字节
        byte highByte = (byte) ((shortValue >> 8) & 0xFF);  // 高字节
        
        // 组合为int（保留符号位）
        return (lowByte << 8) | ((highByte & 0xFF) << 0);
    }

    /**
     * 将int转换为Short（小端模式）, 有符号
     * @param intValue 需要转换的int值
     * @return 转换后的Short值
     * @throws ArithmeticException 当intValue超出Short范围时抛出
     */
    public static short convertIntToShortLittleEndian(int intValue) {
        // 检查是否超出Short范围
        if (intValue < Short.MIN_VALUE || intValue > Short.MAX_VALUE) {
            throw new ArithmeticException("int值超出Short范围，无法转换：" + intValue);
        }
        
        // 小端模式：低字节在前，高字节在后
        byte lowByte = (byte) (intValue & 0xFF);          // 低字节
        byte highByte = (byte) ((intValue >> 8) & 0xFF);  // 高字节
        
        // 组合为short
        return (short) ((lowByte << 8) | (highByte & 0xFF));
    }
}
