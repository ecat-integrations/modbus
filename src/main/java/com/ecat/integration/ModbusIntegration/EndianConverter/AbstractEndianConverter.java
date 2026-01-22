package com.ecat.integration.ModbusIntegration.EndianConverter;

/**
 * 抽象的端序转换器类
 * 
 * 该类提供了获取大端序和小端序转换器单例的方法
 * 
 * @Author coffee
 */
public abstract class AbstractEndianConverter implements EndianConverter {
    // 静态内部类持有单例实例（线程安全+懒加载）
    private static class InstanceHolder {
        // 大端转换器单例（全局唯一）
        static final BigEndianConverter BIG_INSTANCE = new BigEndianConverter();
        // 小端转换器单例（全局唯一）
        static final LittleEndianConverter LITTLE_INSTANCE = new LittleEndianConverter();
    }

    /**
     * 获取大端序转换器单例（全局唯一）
     * @return 大端序转换器实例
     */
    public static BigEndianConverter getBigEndianConverter() {
        return InstanceHolder.BIG_INSTANCE;
    }

    /**
     * 获取小端序转换器单例（全局唯一）
     * @return 小端序转换器实例
     */
    public static LittleEndianConverter getLittleEndianConverter() {
        return InstanceHolder.LITTLE_INSTANCE;
    }
}
