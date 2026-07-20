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

import com.ecat.core.ConfigFlow.ConfigItem.NumericConfigItem;
import com.ecat.core.ConfigFlow.ConfigItem.SchemaConfigItem;
import com.ecat.core.ConfigFlow.ConfigSchema;
import com.ecat.core.ConfigFlow.ConfigSchemaProvider;
import com.ecat.integration.SerialIntegration.ConfigSchemas.SerialCommConfigSchema;

/**
 * Modbus RTU 通讯配置 Schema
 * <p>
 * 嵌套串口配置 {@link SerialCommConfigSchema}，并附加 Modbus 从站 ID：
 * <ul>
 *   <li>serial_settings - 串口设置（引用 SerialCommConfigSchema）</li>
 *   <li>slave_id - Modbus 从站 ID（1-247）</li>
 * </ul>
 *
 * <p>使用方式：无参构造 → 嵌套标准 serial schema（默认 500ms 超时）；
 * {@link #builder()} → 自定义 slaveId，并可用 {@link Builder#serialSchema(ConfigSchema)}
 * 注入一个覆盖默认值后的 serial schema（例如改 serial 超时）：
 * <pre>
 *   ModbusRtuCommConfigSchema.builder()
 *       .slaveId(2)
 *       .serialSchema(SerialCommConfigSchema.builder().timeout(2000).build().createSchema())
 *       .build().createSchema()
 * </pre>
 *
 * @author coffee
 */
public class ModbusRtuCommConfigSchema implements ConfigSchemaProvider {

    // ========== 实例级默认值 ==========
    private static final double DEFAULT_SLAVE_ID = 1.0;
    private final double slaveId;
    /** 注入的 serial schema；null = 走 SerialCommConfigSchema.class 反射（标准默认）。 */
    private final ConfigSchema serialSchema;

    /** 无参构造 → 嵌套标准 serial schema（向后兼容；亦供 .class 反射路径使用）。 */
    public ModbusRtuCommConfigSchema() {
        this.slaveId = DEFAULT_SLAVE_ID;
        this.serialSchema = null;
    }

    private ModbusRtuCommConfigSchema(Builder b) {
        this.slaveId = b.slaveId;
        this.serialSchema = b.serialSchema;
    }

    @Override
    public ConfigSchema createSchema() {
        // serial_settings：注入了自定义 schema 就用实例路径（保留覆盖后的默认值）;
        // 否则走 .class 反射（SerialCommConfigSchema 标准默认）。
        SchemaConfigItem serialSettings = serialSchema != null
            ? new SchemaConfigItem("serial_settings", true, serialSchema)
            : new SchemaConfigItem("serial_settings", true, SerialCommConfigSchema.class);
        return new ConfigSchema()
            .addField(serialSettings
                .displayName("串口设置"))
            .addField(new NumericConfigItem("slave_id", true, slaveId)
                .displayName("从站 ID")
                .range(1.0, 247.0));
    }

    // ========== Builder ==========

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private double slaveId = DEFAULT_SLAVE_ID;
        private ConfigSchema serialSchema = null;   // null = 标准 serial 默认

        public Builder slaveId(double slaveId) { this.slaveId = slaveId; return this; }

        /** 注入一个已构建（可覆盖默认值）的 serial schema；不调用则用 SerialCommConfigSchema 标准默认。 */
        public Builder serialSchema(ConfigSchema serialSchema) { this.serialSchema = serialSchema; return this; }

        public ModbusRtuCommConfigSchema build() {
            return new ModbusRtuCommConfigSchema(this);
        }
    }
}
