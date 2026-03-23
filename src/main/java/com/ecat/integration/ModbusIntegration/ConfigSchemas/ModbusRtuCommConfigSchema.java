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
 * @author coffee
 */
public class ModbusRtuCommConfigSchema implements ConfigSchemaProvider {

    @Override
    public ConfigSchema createSchema() {
        return new ConfigSchema()
            .addField(new SchemaConfigItem("serial_settings", true, SerialCommConfigSchema.class)
                .displayName("串口设置"))
            .addField(new NumericConfigItem("slave_id", true, 1.0)
                .displayName("从站 ID")
                .range(1.0, 247.0));
    }
}
