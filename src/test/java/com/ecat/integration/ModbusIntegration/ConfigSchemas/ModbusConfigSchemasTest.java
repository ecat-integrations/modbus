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

import com.ecat.core.ConfigFlow.ConfigSchema;
import com.ecat.integration.SerialIntegration.ConfigSchemas.SerialCommConfigSchema;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Modbus ConfigSchemas 单元测试
 * <p>
 * 测试三个 Schema 的创建、合法输入验证和非法输入验证。
 *
 * @author coffee
 */
public class ModbusConfigSchemasTest {

    /** 动态获取的系统可用串口，如果无可用串口则为 null */
    private String availablePort;
    
    @Before
    public void setUp() {

        // 注入虚拟串口，使 DynamicEnumConfigItem.validate 能在无物理串口的机器上通过
        SerialCommConfigSchema.setTestPortSupplier(
            () -> SerialCommConfigSchema.createTestPorts("ttyUSB0"));
        availablePort = "ttyUSB0";
    }

    // ========== ModbusCommTypeSchema ==========

    @Test
    public void testCommTypeSchema_createSchema() {
        ModbusCommTypeSchema schema = new ModbusCommTypeSchema();
        ConfigSchema cs = schema.createSchema();
        assertNotNull(cs);
        assertEquals(1, cs.getFields().size());
        assertEquals("modbus_protocol", cs.getFields().get(0).getKey());
    }

    @Test
    public void testCommTypeSchema_validInput_Rtu() {
        ConfigSchema cs = new ModbusCommTypeSchema().createSchema();
        Map<String, Object> input = new HashMap<>();
        input.put("modbus_protocol", "RTU");
        Map<String, Object> errors = cs.validate(input);
        assertTrue("Should have no errors for RTU", errors.isEmpty());
    }

    @Test
    public void testCommTypeSchema_validInput_Tcp() {
        ConfigSchema cs = new ModbusCommTypeSchema().createSchema();
        Map<String, Object> input = new HashMap<>();
        input.put("modbus_protocol", "TCP");
        Map<String, Object> errors = cs.validate(input);
        assertTrue("Should have no errors for TCP", errors.isEmpty());
    }

    @Test
    public void testCommTypeSchema_invalidInput() {
        ConfigSchema cs = new ModbusCommTypeSchema().createSchema();
        Map<String, Object> input = new HashMap<>();
        input.put("modbus_protocol", "INVALID");
        Map<String, Object> errors = cs.validate(input);
        assertFalse("Should have errors for invalid protocol", errors.isEmpty());
    }

    @Test
    public void testCommTypeSchema_missingRequiredField() {
        ConfigSchema cs = new ModbusCommTypeSchema().createSchema();
        Map<String, Object> input = new HashMap<>();
        Map<String, Object> errors = cs.validate(input);
        assertFalse("Should have errors for missing required field", errors.isEmpty());
    }

    // ========== ModbusRtuCommConfigSchema ==========

    @Test
    public void testRtuCommSchema_createSchema() {
        ModbusRtuCommConfigSchema schema = new ModbusRtuCommConfigSchema();
        ConfigSchema cs = schema.createSchema();
        assertNotNull(cs);
        assertEquals(2, cs.getFields().size());
        assertEquals("serial_settings", cs.getFields().get(0).getKey());
        assertEquals("slave_id", cs.getFields().get(1).getKey());
    }

    @Test
    public void testRtuCommSchema_validInput() {
        ConfigSchema cs = new ModbusRtuCommConfigSchema().createSchema();
        Map<String, Object> input = new HashMap<>();
        // Use an actual available port from the system, or the default value
        Map<String, Object> serialSettings = new HashMap<>();
        Map<String, String> availablePorts = SerialCommConfigSchema.getAvailablePorts();
        String portToUse = availablePorts.keySet().stream()
                .filter(p -> !p.isEmpty())
                .findFirst()
                .orElse(availablePorts.keySet().iterator().next());
        serialSettings.put("serial_port", portToUse);
        serialSettings.put("baudrate", "9600");
        serialSettings.put("data_bits", "8");
        serialSettings.put("stop_bits", "1");
        serialSettings.put("parity", "None");
        input.put("serial_settings", serialSettings);
        input.put("slave_id", 1.0);
        Map<String, Object> errors = cs.validate(input);
        assertTrue("Validation errors: " + errors, errors.isEmpty());
    }

    @Test
    public void testRtuCommSchema_missingSlaveId() {
        ConfigSchema cs = new ModbusRtuCommConfigSchema().createSchema();
        Map<String, Object> input = new HashMap<>();
        // Use an actual available port
        Map<String, Object> serialSettings = new HashMap<>();
        Map<String, String> availablePorts = SerialCommConfigSchema.getAvailablePorts();
        String portToUse = availablePorts.keySet().stream()
                .filter(p -> !p.isEmpty())
                .findFirst()
                .orElse(availablePorts.keySet().iterator().next());
        serialSettings.put("serial_port", portToUse);
        serialSettings.put("baudrate", "9600");
        serialSettings.put("data_bits", "8");
        serialSettings.put("stop_bits", "1");
        serialSettings.put("parity", "None");
        input.put("serial_settings", serialSettings);
        // missing slave_id
        Map<String, Object> errors = cs.validate(input);
        assertTrue("Should have slave_id error", errors.containsKey("slave_id"));
    }

    @Test
    public void testRtuCommSchema_slaveIdOutOfRange() {
        ConfigSchema cs = new ModbusRtuCommConfigSchema().createSchema();
        Map<String, Object> input = new HashMap<>();
        // Use an actual available port
        Map<String, Object> serialSettings = new HashMap<>();
        Map<String, String> availablePorts = SerialCommConfigSchema.getAvailablePorts();
        String portToUse = availablePorts.keySet().stream()
                .filter(p -> !p.isEmpty())
                .findFirst()
                .orElse(availablePorts.keySet().iterator().next());
        serialSettings.put("serial_port", portToUse);
        serialSettings.put("baudrate", "9600");
        serialSettings.put("data_bits", "8");
        serialSettings.put("stop_bits", "1");
        serialSettings.put("parity", "None");
        input.put("serial_settings", serialSettings);
        input.put("slave_id", 300.0); // out of range 1-247
        Map<String, Object> errors = cs.validate(input);
        assertTrue("Should have slave_id error", errors.containsKey("slave_id"));
    }

    // ========== ModbusTcpCommConfigSchema ==========

    @Test
    public void testTcpCommSchema_createSchema() {
        ModbusTcpCommConfigSchema schema = new ModbusTcpCommConfigSchema();
        ConfigSchema cs = schema.createSchema();
        assertNotNull(cs);
        assertEquals(5, cs.getFields().size());
        assertEquals("tcp_protocol", cs.getFields().get(0).getKey());
        assertEquals("ip_address", cs.getFields().get(1).getKey());
        assertEquals("port", cs.getFields().get(2).getKey());
        assertEquals("slave_id", cs.getFields().get(3).getKey());
        assertEquals("timeout", cs.getFields().get(4).getKey());
    }

    @Test
    public void testTcpCommSchema_validInput() {
        ConfigSchema cs = new ModbusTcpCommConfigSchema().createSchema();
        Map<String, Object> input = new HashMap<>();
        input.put("tcp_protocol", "TCP");
        input.put("ip_address", "192.168.1.100");
        input.put("port", 502.0);
        input.put("slave_id", 1.0);
        input.put("timeout", 2000.0);
        Map<String, Object> errors = cs.validate(input);
        assertTrue("Validation errors: " + errors, errors.isEmpty());
    }

    @Test
    public void testTcpCommSchema_validInput_withoutTimeout() {
        ConfigSchema cs = new ModbusTcpCommConfigSchema().createSchema();
        Map<String, Object> input = new HashMap<>();
        input.put("tcp_protocol", "TCP");
        input.put("ip_address", "192.168.1.100");
        input.put("port", 502.0);
        input.put("slave_id", 1.0);
        // timeout is optional, should not cause errors
        Map<String, Object> errors = cs.validate(input);
        assertTrue("Validation errors: " + errors, errors.isEmpty());
    }

    @Test
    public void testTcpCommSchema_requiredFieldMissing() {
        ConfigSchema cs = new ModbusTcpCommConfigSchema().createSchema();
        Map<String, Object> input = new HashMap<>();
        // Missing ip_address, port, slave_id
        Map<String, Object> errors = cs.validate(input);
        assertFalse("Should have errors for missing required fields", errors.isEmpty());
    }

    @Test
    public void testTcpCommSchema_portRangeValidation() {
        ConfigSchema cs = new ModbusTcpCommConfigSchema().createSchema();
        Map<String, Object> input = new HashMap<>();
        input.put("tcp_protocol", "TCP");
        input.put("ip_address", "192.168.1.100");
        input.put("port", 99999.0);  // out of range 1-65535
        input.put("slave_id", 1.0);
        Map<String, Object> errors = cs.validate(input);
        assertFalse("Should have errors for out-of-range port", errors.isEmpty());
    }

    @Test
    public void testTcpCommSchema_slaveIdRangeValidation() {
        ConfigSchema cs = new ModbusTcpCommConfigSchema().createSchema();
        Map<String, Object> input = new HashMap<>();
        input.put("tcp_protocol", "TCP");
        input.put("ip_address", "192.168.1.100");
        input.put("port", 502.0);
        input.put("slave_id", 0.0);  // out of range 1-247
        Map<String, Object> errors = cs.validate(input);
        assertFalse("Should have errors for out-of-range slave_id", errors.isEmpty());
    }

    @Test
    public void testTcpCommSchema_ipTooShort() {
        ConfigSchema cs = new ModbusTcpCommConfigSchema().createSchema();
        Map<String, Object> input = new HashMap<>();
        input.put("tcp_protocol", "TCP");
        input.put("ip_address", "abc"); // too short, min 7
        input.put("port", 502.0);
        input.put("slave_id", 1.0);
        Map<String, Object> errors = cs.validate(input);
        assertFalse("Should have errors for IP address too short", errors.isEmpty());
    }

    @Test
    public void testTcpCommSchema_validRtuOverTcp() {
        ConfigSchema cs = new ModbusTcpCommConfigSchema().createSchema();
        Map<String, Object> input = new HashMap<>();
        input.put("tcp_protocol", "RTU_OVER_TCP");
        input.put("ip_address", "192.168.1.100");
        input.put("port", 502.0);
        input.put("slave_id", 1.0);
        Map<String, Object> errors = cs.validate(input);
        assertTrue("Validation errors: " + errors, errors.isEmpty());
    }
}
