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
 * @author coffee
 */
public class ModbusCommTypeSchema implements ConfigSchemaProvider {

    @Override
    public ConfigSchema createSchema() {
        return new ConfigSchema()
            .addField(new EnumConfigItem("modbus_protocol", true, "RTU")
                .displayName("Modbus 协议")
                .addOption("RTU", "Modbus RTU (RS485)")
                .addOption("TCP", "Modbus TCP")
                .buildValidator());
    }
}
