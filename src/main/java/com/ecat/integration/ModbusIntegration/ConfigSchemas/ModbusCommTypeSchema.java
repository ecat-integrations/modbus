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
import com.ecat.core.ConfigFlow.ConfigSchema;
import com.ecat.core.ConfigFlow.ConfigSchemaProvider;

/**
 * Modbus 协议类型选择 Schema
 * <p>
 * 定义 Modbus 通讯协议类型字段：
 * <ul>
 *   <li>modbus_protocol - 协议类型（RTU / TCP）</li>
 * </ul>
 *
 * <p>使用方式：无参构造 → 标准默认值（RTU）；{@link #builder()} → 自定义默认协议。
 *
 * @author coffee
 */
public class ModbusCommTypeSchema implements ConfigSchemaProvider {

    private static final String DEFAULT_MODBUS_PROTOCOL = "RTU";

    private final String modbusProtocol;

    /** 无参构造 → 标准默认值（向后兼容）。 */
    public ModbusCommTypeSchema() {
        this.modbusProtocol = DEFAULT_MODBUS_PROTOCOL;
    }

    private ModbusCommTypeSchema(Builder b) {
        this.modbusProtocol = b.modbusProtocol;
    }

    @Override
    public ConfigSchema createSchema() {
        return new ConfigSchema()
            .addField(new EnumConfigItem("modbus_protocol", true, modbusProtocol)
                .displayName("Modbus 协议")
                .addOption("RTU", "Modbus RTU (RS485)")
                .addOption("TCP", "Modbus TCP")
                .buildValidator());
    }

    // ========== Builder ==========

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String modbusProtocol = DEFAULT_MODBUS_PROTOCOL;

        public Builder modbusProtocol(String modbusProtocol) { this.modbusProtocol = modbusProtocol; return this; }

        public ModbusCommTypeSchema build() {
            return new ModbusCommTypeSchema(this);
        }
    }
}
