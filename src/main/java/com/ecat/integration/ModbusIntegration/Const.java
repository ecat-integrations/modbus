/**
 * This class contains constant values used in the ModbusIntegration module.
 * @author coffee
 */

package com.ecat.integration.ModbusIntegration;

public class Const {
    public static final Integer DEFAULT_WAIT_TIMEOUT_MS = 2000; // 默认等待超时时间（毫秒）
    public static final Integer DEFAULT_MAX_WAITERS = 3; // 默认最大等待请求数
    public static final Integer DEFAULT_TCP_TIMEOUT_MS = 2000; // TCP 事务超时默认值（毫秒），与 ModbusTcpCommConfigSchema 中 timeout 默认值一致
}
