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

package com.ecat.integration.ModbusIntegration.ConfigFlows;

import com.ecat.core.ConfigFlow.AbstractSubConfigFlow;
import com.ecat.core.ConfigFlow.ConfigFlowResult;
import com.ecat.core.ConfigFlow.ConfigSchema;
import com.ecat.integration.ModbusIntegration.ConfigSchemas.ModbusCommTypeSchema;
import com.ecat.integration.ModbusIntegration.ConfigSchemas.ModbusRtuCommConfigSchema;
import com.ecat.integration.ModbusIntegration.ConfigSchemas.ModbusTcpCommConfigSchema;

import java.util.HashMap;
import java.util.Map;

/**
 * Modbus 通讯子 flow：协议选择 → 按协议分流通讯配置 → 落盘 comm_settings。
 *
 * <p>供设备集成宿主 flow 挂载，两种形态：
 * <ul>
 *   <li>{@code registerFlowStep(new ModbusCommFlow(), "宿主尾步")} —— 通讯参数全取库标准默认；</li>
 *   <li>{@code registerFlowStep(ModbusCommFlow.builder().rtu(...).tcp(...).build(), "宿主尾步")}
 *       —— 宿主定制 schema 默认值（预填从站/IP/超时等），定制面=三个 schema 既有的 builder，
 *       子 flow 不新造参数名。</li>
 * </ul>
 * 落盘形状与 modbus 域现行主流完全一致（存量 entry 零迁移）：
 * <ul>
 *   <li>modbus_protocol —— 顶层（protocol_select 步 putAll）</li>
 *   <li>comm_settings —— 整块（RTU={serial_settings{...}, slave_id}；
 *       TCP={tcp_protocol, ip_address, port, slave_id, timeout}）</li>
 * </ul>
 * 设备侧读出经 {@link com.ecat.integration.ModbusIntegration.ModbusCommSettings#parse}（唯一合法通道）。
 *
 * @author coffee
 */
public class ModbusCommFlow extends AbstractSubConfigFlow {

    private final ModbusCommTypeSchema typeSchema;
    private final ModbusRtuCommConfigSchema rtuSchema;
    private final ModbusTcpCommConfigSchema tcpSchema;

    /** 无参构造 = 三个 schema 全取库标准默认（最简挂载形态）。 */
    public ModbusCommFlow() {
        this.typeSchema = new ModbusCommTypeSchema();
        this.rtuSchema = new ModbusRtuCommConfigSchema();
        this.tcpSchema = new ModbusTcpCommConfigSchema();
        registerSteps();
    }

    private ModbusCommFlow(Builder b) {
        this.typeSchema = b.typeSchema;
        this.rtuSchema = b.rtuSchema;
        this.tcpSchema = b.tcpSchema;
        registerSteps();
    }

    private void registerSteps() {
        registerStepEntry("protocol_select", this::stepProtocolSelect, "协议选择");   // 入口显式声明
        registerStep("comm_config", this::stepCommConfig, "通讯配置");
    }

    private ConfigFlowResult stepProtocolSelect(Map<String, Object> userInput) {
        if (userInput == null || userInput.isEmpty()) {
            return showForm("protocol_select", typeSchema.createSchema(), new HashMap<>());
        }
        ConfigSchema schema = typeSchema.createSchema();
        Map<String, Object> errors = schema.validate(userInput);
        if (!errors.isEmpty()) {
            return showForm("protocol_select", schema, errors);
        }
        // 保存选择的协议类型
        context.getEntryData().putAll(userInput);
        // 根据协议类型选择通讯配置 Schema
        String protocol = (String) userInput.get("modbus_protocol");
        return showForm("comm_config", createCommConfigSchema(protocol), new HashMap<>());
    }

    private ConfigFlowResult stepCommConfig(Map<String, Object> userInput) {
        String protocol = (String) context.getEntryData().getOrDefault("modbus_protocol", "RTU");
        if (userInput == null || userInput.isEmpty()) {
            // 根据已选择的协议类型显示对应 Schema
            return showForm("comm_config", createCommConfigSchema(protocol), new HashMap<>());
        }
        ConfigSchema schema = createCommConfigSchema(protocol);
        Map<String, Object> errors = schema.validate(userInput);
        if (!errors.isEmpty()) {
            return showForm("comm_config", schema, errors);
        }
        context.getEntryData().put("comm_settings", userInput);
        return subFlowComplete();   // 出口：交回宿主尾步（挂载时由宿主指定）
    }

    /** 根据协议类型创建通讯配置 Schema（"RTU" 或 "TCP"）——用持有实例（可能是宿主定制过默认值的）。 */
    private ConfigSchema createCommConfigSchema(String protocol) {
        if ("TCP".equals(protocol)) {
            return tcpSchema.createSchema();
        }
        // 默认 RTU
        return rtuSchema.createSchema();
    }

    // ========== Builder：宿主定制 schema 默认值的唯一通道 ==========

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 三个槽位不设置 = 库标准 schema；宿主传入自己用 schema builder 构建的实例覆盖表单预填。
     * 定制只影响写端预填——读端 parse 的缺字段兜底恒为库标准。
     */
    public static class Builder {
        private ModbusCommTypeSchema typeSchema = new ModbusCommTypeSchema();
        private ModbusRtuCommConfigSchema rtuSchema = new ModbusRtuCommConfigSchema();
        private ModbusTcpCommConfigSchema tcpSchema = new ModbusTcpCommConfigSchema();

        /** 定制协议选择步默认项（预选 RTU/TCP）。 */
        public Builder type(ModbusCommTypeSchema typeSchema) { this.typeSchema = typeSchema; return this; }

        /** 定制 RTU 步：从站默认 + 注入定制 serial schema（超时/波特率等预填）。 */
        public Builder rtu(ModbusRtuCommConfigSchema rtuSchema) { this.rtuSchema = rtuSchema; return this; }

        /** 定制 TCP 步：IP 预填、端口、tcp_protocol 默认、超时。 */
        public Builder tcp(ModbusTcpCommConfigSchema tcpSchema) { this.tcpSchema = tcpSchema; return this; }

        public ModbusCommFlow build() { return new ModbusCommFlow(this); }
    }
}
