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

/**
 * Modbus TCP 通讯配置 Schema
 * <p>
 * 定义 Modbus TCP 连接所需的配置字段：
 * <ul>
 *   <li>tcp_protocol - TCP 协议模式（TCP / RTU_OVER_TCP）</li>
 *   <li>ip_address - 设备 IP 地址</li>
 *   <li>port - 通讯端口（默认 502）</li>
 *   <li>slave_id - Modbus 从站 ID（1-247）</li>
 *   <li>timeout - 超时时间(ms)，可选</li>
 * </ul>
 *
 * @author coffee
 */
public class ModbusTcpCommConfigSchema implements ConfigSchemaProvider {

    @Override
    public ConfigSchema createSchema() {
        return new ConfigSchema()
            .addField(new EnumConfigItem("tcp_protocol", true, "TCP")
                .displayName("TCP 协议模式")
                .addOption("TCP", "Modbus TCP (MBAP帧)")
                .addOption("RTU_OVER_TCP", "Modbus RTU over TCP")
                .buildValidator())
            .addField(new TextConfigItem("ip_address", true)
                .displayName("IP 地址")
                .length(7, 45))
            .addField(new NumericConfigItem("port", true, 502.0)
                .displayName("端口")
                .range(1.0, 65535.0))
            .addField(new NumericConfigItem("slave_id", true, 1.0)
                .displayName("从站 ID")
                .range(1.0, 247.0))
            .addField(new NumericConfigItem("timeout", false, 2000.0)
                .displayName("超时时间(ms)")
                .range(100.0, 30000.0));
    }
}
