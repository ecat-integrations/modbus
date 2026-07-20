package com.ecat.integration.ModbusIntegration.ConfigSchemas;

import com.ecat.core.ConfigFlow.ConfigItem.AbstractConfigItem;
import com.ecat.core.ConfigFlow.ConfigItem.SchemaConfigItem;
import com.ecat.core.ConfigFlow.ConfigSchema;
import com.ecat.integration.ModbusIntegration.Const;
import com.ecat.integration.SerialIntegration.ConfigSchemas.SerialCommConfigSchema;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Modbus comm schema Builder 行为测试:
 * 无参标准默认、Builder 覆盖、ModbusTcp 超时与 Const 同源(双默认防回退)、ModbusRtu 嵌套 serial 覆盖。
 */
public class ModbusCommConfigSchemaTest {

    private static AbstractConfigItem<?> field(ConfigSchema s, String key) {
        return s.getFields().stream().filter(f -> f.getKey().equals(key)).findFirst().orElse(null);
    }

    // ===== ModbusTcp =====

    @Test
    public void tcp_timeoutDefaultSameAsConst_singleSource() {
        ConfigSchema s = new ModbusTcpCommConfigSchema().createSchema();
        // schema 预填默认必须与驱动兜底 Const.DEFAULT_TCP_TIMEOUT_MS 同源(防双默认打架)
        assertEquals(Const.DEFAULT_TCP_TIMEOUT_MS.doubleValue(),
            ((Number) field(s, "timeout").getDefaultValue()).doubleValue(), 0.001);
        assertEquals(502.0, field(s, "port").getDefaultValue());
        assertEquals(1.0, field(s, "slave_id").getDefaultValue());
        assertNull("ip_address 默认必填无预填", field(s, "ip_address").getDefaultValue());
    }

    @Test
    public void tcp_builderOverride_takesEffect() {
        ConfigSchema s = ModbusTcpCommConfigSchema.builder()
            .ipAddress("192.168.1.5")
            .port(503)
            .slaveId(2)
            .timeout(5000)
            .build().createSchema();
        assertEquals("192.168.1.5", field(s, "ip_address").getDefaultValue());
        assertEquals(503.0, field(s, "port").getDefaultValue());
        assertEquals(2.0, field(s, "slave_id").getDefaultValue());
        assertEquals(5000.0, ((Number) field(s, "timeout").getDefaultValue()).doubleValue(), 0.001);
    }

    // ===== ModbusRtu(嵌套 serial) =====

    @Test
    public void rtu_noArg_embedsStandardSerial() {
        ConfigSchema s = new ModbusRtuCommConfigSchema().createSchema();
        SchemaConfigItem sci = (SchemaConfigItem) field(s, "serial_settings");
        assertNotNull(sci);
        ConfigSchema serial = sci.resolveSchema();
        // 标准 serial 默认超时 = 500ms
        assertEquals(500.0, ((Number) field(serial, "timeout").getDefaultValue()).doubleValue(), 0.001);
        assertEquals(1.0, field(s, "slave_id").getDefaultValue());
    }

    @Test
    public void rtu_builderInjects_overriddenSerialSchema() {
        // 嵌一个覆盖了 serial 超时的 schema
        ConfigSchema overriddenSerial = SerialCommConfigSchema.builder()
            .timeout(2000).build().createSchema();
        ConfigSchema s = ModbusRtuCommConfigSchema.builder()
            .slaveId(3)
            .serialSchema(overriddenSerial)
            .build().createSchema();
        SchemaConfigItem sci = (SchemaConfigItem) field(s, "serial_settings");
        ConfigSchema resolved = sci.resolveSchema();
        // resolveSchema 返回注入的实例(超时 2000,非标准 500)
        assertEquals("注入的 serial 覆盖应生效", 2000.0,
            ((Number) field(resolved, "timeout").getDefaultValue()).doubleValue(), 0.001);
        assertEquals(3.0, field(s, "slave_id").getDefaultValue());
    }

    // ===== ModbusCommType =====

    @Test
    public void commType_noArgAndBuilder() {
        assertEquals("RTU", new ModbusCommTypeSchema().createSchema().getFields().get(0).getDefaultValue());
        assertEquals("TCP",
            ModbusCommTypeSchema.builder().modbusProtocol("TCP").build().createSchema().getFields().get(0).getDefaultValue());
    }
}
