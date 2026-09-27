/*
 * Copyright (c) 2026 ECAT Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ecat.integration.ModbusIntegration;

import java.util.Locale;
import java.util.Map;

/**
 * comm_settings 类型化读端：裸 Map → {@link ModbusInfo}。
 *
 * <p>设备集成侧唯一合法读出通道（与 {@link ConfigFlows.ModbusCommFlow} 写端同仓同源演进，
 * comm_settings 形状变更对采纳仓爆炸半径为零）。严格模式：key 缺失/类型不符
 * 抛带字段名的 IllegalArgumentException，不吞、不返回 null 掩盖。
 *
 * @author coffee
 */
public final class ModbusCommSettings {

    private ModbusCommSettings() {
    }

    /**
     * @param entryData ConfigEntry.data（含顶层 modbus_protocol 与 comm_settings 整块）
     * @return RTU → {@link ModbusSerialInfo}；TCP → {@link ModbusTcpInfo}（tcp_protocol=RTU_OVER_TCP 时
     *         frameFormat=ModbusProtocol.RTU_OVER_TCP，否则 TCP）
     */
    public static ModbusInfo parse(Map<String, Object> entryData) {
        String protocol = str(entryData, "modbus_protocol");
        Map<String, Object> comm = map(entryData, "comm_settings");
        if ("TCP".equals(protocol)) {
            String frame = comm.containsKey("tcp_protocol")
                    ? str(comm, "tcp_protocol") : "TCP";
            ModbusProtocol frameFormat = toFrameFormat(frame);
            // timeout 缺省与 modbus 库 schema 预填同源（Const 与本类同包，无需 import）
            int timeout = comm.containsKey("timeout")
                    ? toInt(comm, "timeout") : Const.DEFAULT_TCP_TIMEOUT_MS;
            return new ModbusTcpInfo(str(comm, "ip_address"),
                    toInt(comm, "port"), toInt(comm, "slave_id"), frameFormat, timeout);
        }
        // 默认 RTU：serial_settings 嵌套块 + slave_id。
        // timeout 缺省与 serial 库 schema 预填同源——限定名引用是为避开与本包 modbus Const 的重名
        // （modbus 已依赖 serial：ModbusRtuCommConfigSchema 内嵌 SerialCommConfigSchema）。
        Map<String, Object> serial = map(comm, "serial_settings");
        int timeout = serial.containsKey("timeout")
                ? toInt(serial, "timeout")
                : com.ecat.integration.SerialIntegration.Const.READ_TIMEOUT_MS;
        return new ModbusSerialInfo(
                str(serial, "serial_port"),
                toInt(serial, "baudrate"),
                toInt(serial, "data_bits"),
                toStopBits(str(serial, "stop_bits")),
                toParity(str(serial, "parity")),
                timeout,
                toInt(comm, "slave_id"));
    }

    /** schema 枚举值 → 帧格式；未知值抛带字段名的异常（valueOf 裸抛不带字段名，不满足本类契约）。 */
    private static ModbusProtocol toFrameFormat(String v) {
        try {
            return ModbusProtocol.valueOf(v);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("comm_settings tcp_protocol 取值不支持: " + v);
        }
    }

    /**
     * schema 枚举值 "1"/"2" → jSerialComm 风格停止位常量。
     * 不能数字直传：TWO_STOP_BITS=3 而非 2（2 是 ONE_POINT_FIVE_STOP_BITS），必须显式映射。
     */
    private static int toStopBits(String v) {
        switch (v) {
            case "1": return ModbusSerialInfo.ONE_STOP_BIT;
            case "2": return ModbusSerialInfo.TWO_STOP_BITS;
            default: throw new IllegalArgumentException("comm_settings stop_bits 取值不支持: " + v);
        }
    }

    /**
     * 校验位枚举值 → ModbusSerialInfo 常量。
     * serial 库 schema 的 Parity 枚举值是 title case（"None"/"Odd"/"Even"），先统一大写再匹配
     * （与 modbus 域现行手抄解析同款行为）；未知值严格抛，不做静默兜底。
     */
    private static int toParity(String v) {
        switch (v.toUpperCase(Locale.ROOT)) {
            case "NONE": return ModbusSerialInfo.NO_PARITY;
            case "ODD": return ModbusSerialInfo.ODD_PARITY;
            case "EVEN": return ModbusSerialInfo.EVEN_PARITY;
            case "MARK": return ModbusSerialInfo.MARK_PARITY;
            case "SPACE": return ModbusSerialInfo.SPACE_PARITY;
            default: throw new IllegalArgumentException("comm_settings parity 取值不支持: " + v);
        }
    }

    private static String str(Map<String, Object> data, String key) {
        Object v = data.get(key);
        if (v == null) {
            throw new IllegalArgumentException("comm_settings 字段缺失: " + key);
        }
        return v.toString();
    }

    private static int toInt(Map<String, Object> data, String key) {
        Object v = data.get(key);
        if (v instanceof Number) {
            return (int) Math.round(((Number) v).doubleValue());
        }
        if (v instanceof String) {
            try {
                // 容忍浮点字符串（"500.0"→500）：schema defaultValue 是数值型，API 消费方会把默认值
                // 字符串化——Integer.parseInt 不认浮点，这里统一容忍
                return (int) Math.round(Double.parseDouble(((String) v).trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("comm_settings 字段不是数值: " + key + "=" + v);
            }
        }
        throw new IllegalArgumentException("comm_settings 字段缺失或类型不符: " + key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> data, String key) {
        Object v = data.get(key);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("comm_settings 字段缺失或不是对象: " + key);
        }
        return (Map<String, Object>) v;
    }
}
