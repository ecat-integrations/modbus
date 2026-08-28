/**
 * This class contains constant values used in the ModbusIntegration module.
 * @author coffee
 */

package com.ecat.integration.ModbusIntegration;

public class Const {
    public static final Integer DEFAULT_WAIT_TIMEOUT_MS = 2000; // 默认等待超时时间（毫秒）
    public static final Integer DEFAULT_MAX_WAITERS = 3; // 默认最大等待请求数
    public static final Integer DEFAULT_TCP_TIMEOUT_MS = 2000; // TCP 事务超时默认值（毫秒），与 ModbusTcpCommConfigSchema 中 timeout 默认值一致
    /**
     * modbus IO 旁池默认线程数（R3 期 4，15 号设计 §6.4 D4 决策）：阻塞 send 的专职有界池
     * {@code ecat-modbus-io-N} 定容。同源引用两处（F-26 同源纪律）：ConfigItem 默认值
     * （ModbusIntegration.getConfigDefinition）与 onInit 读取 fallback，防 schema 默认与
     * 运行时常量静默漂移。范围 1..64，默认 16 起步按观测调整。
     */
    public static final Integer DEFAULT_IO_POOL_SIZE = 16;
}
