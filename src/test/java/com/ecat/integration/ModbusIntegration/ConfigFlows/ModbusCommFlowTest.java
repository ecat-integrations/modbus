package com.ecat.integration.ModbusIntegration.ConfigFlows;

import com.ecat.core.ConfigFlow.AbstractConfigFlow;
import com.ecat.core.ConfigFlow.ConfigFlowResult;
import com.ecat.core.ConfigFlow.ConfigItem.AbstractConfigItem;
import com.ecat.core.ConfigFlow.ConfigItem.SchemaConfigItem;
import com.ecat.core.ConfigFlow.ConfigSchema;
import com.ecat.core.ConfigFlow.FlowContext;
import com.ecat.integration.ModbusIntegration.ConfigSchemas.ModbusRtuCommConfigSchema;
import com.ecat.integration.ModbusIntegration.ConfigSchemas.ModbusTcpCommConfigSchema;
import com.ecat.integration.SerialIntegration.ConfigSchemas.SerialCommConfigSchema;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * ModbusCommFlow（Modbus 通讯子 flow）单元测试：
 * 挂载走读（协议选择 → 分流配置 → 出口交回宿主尾步）、落盘形状、
 * 定制形态对照（宿主经 builder 注入定制预填 vs 无参挂载用库标准预填）。
 */
public class ModbusCommFlowTest {

    @BeforeClass
    public static void injectTestSerialPort() {
        // serial_port 是动态枚举（校验成员资格），测试环境无真实串口——注入虚拟端口
        Map<String, String> ports = new LinkedHashMap<>();
        ports.put("/dev/ttyUSB0", "/dev/ttyUSB0");
        SerialCommConfigSchema.setTestPortSupplier(() -> ports);
    }

    @AfterClass
    public static void clearTestSerialPort() {
        SerialCommConfigSchema.clearTestPortSupplier();
    }

    /** 测试宿主：user 入口 + device_config + final_confirm，挂载被测子 flow，device_config 末尾进入。 */
    private static class HostFlow extends AbstractConfigFlow {

        final String commStepId;

        HostFlow(ModbusCommFlow commFlow) {
            super();
            registerStepUser("user", "配置设备", this::stepUser);
            registerStep("device_config", this::stepDeviceConfig, "设备配置");
            registerStep("final_confirm", this::stepFinalConfirm, "确认配置");
            commStepId = registerFlowStep(commFlow, "final_confirm");
        }

        private ConfigFlowResult stepUser(Map<String, Object> userInput, FlowContext ctx) {
            if (userInput == null || userInput.isEmpty()) {
                return showForm("user", new ConfigSchema(), new HashMap<>());
            }
            return showForm("device_config", new ConfigSchema(), new HashMap<>());
        }

        private ConfigFlowResult stepDeviceConfig(Map<String, Object> userInput) {
            if (userInput == null || userInput.isEmpty()) {
                return showForm("device_config", new ConfigSchema(), new HashMap<>());
            }
            context.getEntryData().putAll(userInput);
            return handleStep(commStepId, null);
        }

        private ConfigFlowResult stepFinalConfirm(Map<String, Object> userInput) {
            if (userInput == null || userInput.isEmpty()) {
                return showForm("final_confirm", new ConfigSchema(), new HashMap<>());
            }
            return createEntry();
        }
    }

    /** 驱动宿主直到子 flow 首屏（protocol_select）显示。 */
    private static HostFlow enterSubFlow(ModbusCommFlow commFlow) {
        HostFlow host = new HostFlow(commFlow);
        host.executeUserStep(null);
        host.handleStep("user", input("k", "v"));
        ConfigFlowResult r = host.handleStep("device_config", input("name", "dev"));
        assertEquals(ConfigFlowResult.ResultType.SHOW_FORM, r.getType());
        assertEquals("宿主 device_config 提交后应进子 flow 入口屏", "protocol_select", r.getStepId());
        return host;
    }

    // ==================== 挂载走读 ====================

    @Test
    public void rtuPath_submitCommConfig_exitsToHostTail_andPersistsShape() {
        HostFlow host = enterSubFlow(new ModbusCommFlow());

        // 选 RTU → comm_config（RTU schema）
        ConfigFlowResult commForm = host.handleStep("protocol_select", input("modbus_protocol", "RTU"));
        assertEquals("comm_config", commForm.getStepId());

        // 提交通讯配置 → 出口翻译 → 宿主尾步回显
        Map<String, Object> commInput = rtuInput(1, 500);
        ConfigFlowResult exit = host.handleStep("comm_config", commInput);
        assertEquals("子 flow 出口应交回宿主尾步（errors=" + exit.getErrors() + "）",
                ConfigFlowResult.ResultType.SHOW_FORM, exit.getType());
        assertEquals("提交合法通讯配置应落宿主尾步（errors=" + exit.getErrors() + "）",
                "final_confirm", exit.getStepId());
        assertEquals("final_confirm", host.getCurrentStep());

        // 落盘形状：modbus_protocol 顶层 + comm_settings 整块（与现行主流一致，存量 entry 零迁移）
        Map<String, Object> entryData = host.getContext().getEntryData();
        assertEquals("RTU", entryData.get("modbus_protocol"));
        assertEquals(commInput, entryData.get("comm_settings"));
        // 漫游面：子 flow 步数据进宿主 stepInputs
        assertTrue(host.getContext().getStepInputs().containsKey("protocol_select"));
        assertTrue(host.getContext().getStepInputs().containsKey("comm_config"));
    }

    @Test
    public void tcpPath_branchesToTcpSchema() {
        HostFlow host = enterSubFlow(new ModbusCommFlow());

        ConfigFlowResult commForm = host.handleStep("protocol_select", input("modbus_protocol", "TCP"));
        assertEquals("comm_config", commForm.getStepId());
        assertNotNull("TCP 分流后 comm_config 表单应含 ip_address",
                field(commForm.getSchema(), "ip_address"));

        Map<String, Object> commInput = new LinkedHashMap<>();
        commInput.put("tcp_protocol", "TCP");
        commInput.put("ip_address", "192.168.1.50");
        commInput.put("port", 502);
        commInput.put("slave_id", 1);
        commInput.put("timeout", 2000);
        ConfigFlowResult exit = host.handleStep("comm_config", commInput);
        assertEquals("final_confirm", exit.getStepId());
        assertEquals(commInput, host.getContext().getEntryData().get("comm_settings"));
    }

    @Test
    public void protocolSelect_invalidValue_redisplaysWithErrors() {
        HostFlow host = enterSubFlow(new ModbusCommFlow());
        ConfigFlowResult r = host.handleStep("protocol_select", input("modbus_protocol", "XYZ"));
        assertEquals("非法协议值应回显协议选择步", "protocol_select", r.getStepId());
        assertNotNull("应携带校验错误", r.getErrors().get("modbus_protocol"));
    }

    // ==================== 定制形态 vs 无参形态（builder 定制预填 vs 库标准预填） ====================

    @Test
    public void noArgMount_prefillsLibraryStandard() {
        HostFlow host = enterSubFlow(new ModbusCommFlow());
        ConfigFlowResult commForm = host.handleStep("protocol_select", input("modbus_protocol", "RTU"));

        SchemaConfigItem serialSettings = (SchemaConfigItem) field(commForm.getSchema(), "serial_settings");
        ConfigSchema serial = serialSettings.resolveSchema();
        assertEquals("无参挂载：serial timeout 预填 = serial 库标准默认",
                com.ecat.integration.SerialIntegration.Const.READ_TIMEOUT_MS.doubleValue(),
                ((Number) field(serial, "timeout").getDefaultValue()).doubleValue(), 0.001);
        assertEquals("无参挂载：从站 ID 预填 = 库标准 1",
                1.0, ((Number) field(commForm.getSchema(), "slave_id").getDefaultValue()).doubleValue(), 0.001);
    }

    @Test
    public void builderMount_prefillsCustomizedDefaults() {
        ModbusCommFlow customized = ModbusCommFlow.builder()
                .rtu(ModbusRtuCommConfigSchema.builder()
                        .slaveId(2)
                        .serialSchema(SerialCommConfigSchema.builder().timeout(2000).build().createSchema())
                        .build())
                .tcp(ModbusTcpCommConfigSchema.builder()
                        .ipAddress("192.168.1.100")
                        .build())
                .build();

        HostFlow host = enterSubFlow(customized);
        ConfigFlowResult commForm = host.handleStep("protocol_select", input("modbus_protocol", "RTU"));

        SchemaConfigItem serialSettings = (SchemaConfigItem) field(commForm.getSchema(), "serial_settings");
        ConfigSchema serial = serialSettings.resolveSchema();
        assertEquals("builder 定制：serial timeout 预填 = 宿主定制值",
                2000.0, ((Number) field(serial, "timeout").getDefaultValue()).doubleValue(), 0.001);
        assertEquals("builder 定制：从站 ID 预填 = 宿主定制值",
                2.0, ((Number) field(commForm.getSchema(), "slave_id").getDefaultValue()).doubleValue(), 0.001);

        // 提交后落盘带定制值（写端预填随提交进 entry）
        Map<String, Object> commInput = rtuInput(2, 2000);
        host.handleStep("comm_config", commInput);
        assertEquals(commInput, host.getContext().getEntryData().get("comm_settings"));
    }

    @Test
    public void builderMount_tcpIpPrefill_applied() {
        ModbusCommFlow customized = ModbusCommFlow.builder()
                .tcp(ModbusTcpCommConfigSchema.builder()
                        .ipAddress("192.168.1.100")
                        .timeout(3000)
                        .build())
                .build();
        HostFlow host = enterSubFlow(customized);
        ConfigFlowResult commForm = host.handleStep("protocol_select", input("modbus_protocol", "TCP"));
        assertEquals("TCP IP 预填 = 宿主定制值", "192.168.1.100",
                field(commForm.getSchema(), "ip_address").getDefaultValue());
        assertEquals("TCP timeout 预填 = 宿主定制值", 3000.0,
                ((Number) field(commForm.getSchema(), "timeout").getDefaultValue()).doubleValue(), 0.001);
    }

    // ==================== 辅助 ====================

    private static Map<String, Object> rtuInput(int slaveId, int timeout) {
        // 枚举字段（baudrate/data_bits/stop_bits/parity）按真实表单行为提交字符串值
        Map<String, Object> serial = new LinkedHashMap<>();
        serial.put("serial_port", "/dev/ttyUSB0");
        serial.put("baudrate", "9600");
        serial.put("data_bits", "8");
        serial.put("stop_bits", "1");
        serial.put("parity", "None");
        serial.put("timeout", timeout);
        Map<String, Object> comm = new LinkedHashMap<>();
        comm.put("serial_settings", serial);
        comm.put("slave_id", slaveId);
        return comm;
    }

    private static AbstractConfigItem<?> field(ConfigSchema s, String key) {
        return s.getFields().stream().filter(f -> f.getKey().equals(key)).findFirst().orElse(null);
    }

    private static Map<String, Object> input(String k, Object v) {
        Map<String, Object> m = new HashMap<>();
        m.put(k, v);
        return m;
    }
}
