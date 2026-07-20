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

package com.ecat.integration.ModbusIntegration.ConfigSchemas;

import com.ecat.core.ConfigFlow.ConfigItem.EnumConfigItem;
import com.ecat.core.ConfigFlow.ConfigItem.NumericConfigItem;
import com.ecat.core.ConfigFlow.ConfigItem.TextConfigItem;
import com.ecat.core.ConfigFlow.ConfigSchema;
import com.ecat.core.ConfigFlow.ConfigSchemaProvider;
import com.ecat.integration.ModbusIntegration.Const;

/**
 * Modbus TCP 通讯配置 Schema
 * <p>
 * 定义 Modbus TCP 连接所需的配置字段：
 * <ul>
 *   <li>tcp_protocol - TCP 协议模式（TCP / RTU_OVER_TCP）</li>
 *   <li>ip_address - 设备 IP 地址</li>
 *   <li>port - 通讯端口（默认 502）</li>
 *   <li>slave_id - Modbus 从站 ID（1-247）</li>
 *   <li>timeout - 超时时间(ms)，可选（默认取 {@link Const#DEFAULT_TCP_TIMEOUT_MS}，与驱动兜底同源）</li>
 * </ul>
 *
 * <p>使用方式：无参构造 → 标准默认值；{@link #builder()} → 自定义默认值。
 *
 * @author coffee
 */
public class ModbusTcpCommConfigSchema implements ConfigSchemaProvider {

    // ========== 标准默认值（单一来源） ==========
    private static final String DEFAULT_TCP_PROTOCOL = "TCP";
    private static final double DEFAULT_PORT = 502.0;
    private static final double DEFAULT_SLAVE_ID = 1.0;
    // timeout 默认引用 Const.DEFAULT_TCP_TIMEOUT_MS：schema 预填与驱动兜底同源,避免双默认打架

    // ========== 实例级默认值 ==========
    private final String tcpProtocol;
    private final String ipAddress;          // null = 必填无预填（默认）
    private final double port;
    private final double slaveId;
    private final double timeout;

    /** 无参构造 → 标准默认值（向后兼容；亦供 SchemaConfigItem .class 反射路径使用）。 */
    public ModbusTcpCommConfigSchema() {
        this.tcpProtocol = DEFAULT_TCP_PROTOCOL;
        this.ipAddress = null;               // 必填,无预填
        this.port = DEFAULT_PORT;
        this.slaveId = DEFAULT_SLAVE_ID;
        this.timeout = Const.DEFAULT_TCP_TIMEOUT_MS;
    }

    private ModbusTcpCommConfigSchema(Builder b) {
        this.tcpProtocol = b.tcpProtocol;
        this.ipAddress = b.ipAddress;
        this.port = b.port;
        this.slaveId = b.slaveId;
        this.timeout = b.timeout;
    }

    @Override
    public ConfigSchema createSchema() {
        ConfigSchema schema = new ConfigSchema()
            .addField(new EnumConfigItem("tcp_protocol", true, tcpProtocol)
                .displayName("TCP 协议模式")
                .addOption("TCP", "Modbus TCP (MBAP帧)")
                .addOption("RTU_OVER_TCP", "Modbus RTU over TCP")
                .buildValidator());
        // ip_address：null = 必填无预填；否则必填带预填
        if (ipAddress != null) {
            schema.addField(new TextConfigItem("ip_address", true, ipAddress)
                .displayName("IP 地址")
                .length(7, 45));
        } else {
            schema.addField(new TextConfigItem("ip_address", true)
                .displayName("IP 地址")
                .length(7, 45));
        }
        return schema
            .addField(new NumericConfigItem("port", true, port)
                .displayName("端口")
                .range(1.0, 65535.0))
            .addField(new NumericConfigItem("slave_id", true, slaveId)
                .displayName("从站 ID")
                .range(1.0, 247.0))
            .addField(new NumericConfigItem("timeout", false, timeout)
                .displayName("超时时间(ms)")
                .range(100.0, 30000.0));
    }

    // ========== Builder ==========

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String tcpProtocol = DEFAULT_TCP_PROTOCOL;
        private String ipAddress = null;      // 默认必填无预填
        private double port = DEFAULT_PORT;
        private double slaveId = DEFAULT_SLAVE_ID;
        private double timeout = Const.DEFAULT_TCP_TIMEOUT_MS;

        public Builder tcpProtocol(String tcpProtocol) { this.tcpProtocol = tcpProtocol; return this; }
        /** ip_address 预填默认值；不调用则字段必填无预填。 */
        public Builder ipAddress(String ipAddress) { this.ipAddress = ipAddress; return this; }
        public Builder port(double port) { this.port = port; return this; }
        public Builder slaveId(double slaveId) { this.slaveId = slaveId; return this; }
        public Builder timeout(double timeout) { this.timeout = timeout; return this; }

        public ModbusTcpCommConfigSchema build() {
            return new ModbusTcpCommConfigSchema(this);
        }
    }
}
