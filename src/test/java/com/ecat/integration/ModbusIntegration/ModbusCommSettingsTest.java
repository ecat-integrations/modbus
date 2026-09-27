package com.ecat.integration.ModbusIntegration;

import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * ModbusCommSettings（comm_settings 类型化读端）单元测试：
 * RTU/TCP 形状映射、枚举→常量换算（含 TWO_STOP_BITS=3 陷阱）、缺省同源、严格抛错、浮点字符串容忍。
 */
public class ModbusCommSettingsTest {

    // ==================== RTU ====================

    @Test
    public void rtu_fullShape_mapsConstantsAndFields() {
        Map<String, Object> serial = new LinkedHashMap<>();
        serial.put("serial_port", "/dev/ttyUSB0");
        serial.put("baudrate", 9600);
        serial.put("data_bits", 8);
        serial.put("stop_bits", "2");
        serial.put("parity", "Even");           // serial 库 schema 实际值是 title case
        serial.put("timeout", 1500);
        Map<String, Object> comm = new LinkedHashMap<>();
        comm.put("serial_settings", serial);
        comm.put("slave_id", 3);
        Map<String, Object> entryData = new HashMap<>();
        entryData.put("modbus_protocol", "RTU");
        entryData.put("comm_settings", comm);

        ModbusInfo info = ModbusCommSettings.parse(entryData);
        assertTrue(info instanceof ModbusSerialInfo);
        assertEquals(ModbusProtocol.SERIAL, info.getProtocol());
        assertEquals(3, info.getSlaveId().intValue());

        ModbusSerialInfo serialInfo = (ModbusSerialInfo) info;
        assertEquals("/dev/ttyUSB0", serialInfo.getPortName());
        assertEquals(9600, serialInfo.getBaudrate());
        assertEquals(8, serialInfo.getDataBits());
        assertEquals("stop_bits \"2\" 必须映射 TWO_STOP_BITS=3（2 是 1.5 位，直传会静默错位）",
                ModbusSerialInfo.TWO_STOP_BITS, serialInfo.getStopBits());
        assertEquals(ModbusSerialInfo.EVEN_PARITY, serialInfo.getParity());
        assertEquals(1500, serialInfo.getTimeout());
    }

    @Test
    public void rtu_parityCaseInsensitive_legacyUppercaseAccepted() {
        Map<String, Object> entryData = rtuEntry("parity", "NONE");
        ModbusSerialInfo info = (ModbusSerialInfo) ModbusCommSettings.parse(entryData);
        assertEquals(ModbusSerialInfo.NO_PARITY, info.getParity());
    }

    @Test
    public void rtu_missingTimeout_fallsBackToSerialSchemaPrefill() {
        Map<String, Object> serial = new LinkedHashMap<>();
        serial.put("serial_port", "/dev/ttyUSB0");
        serial.put("baudrate", 9600);
        serial.put("data_bits", 8);
        serial.put("stop_bits", "1");
        serial.put("parity", "None");
        Map<String, Object> comm = new LinkedHashMap<>();
        comm.put("serial_settings", serial);
        comm.put("slave_id", 1);
        Map<String, Object> entryData = new HashMap<>();
        entryData.put("modbus_protocol", "RTU");
        entryData.put("comm_settings", comm);

        ModbusSerialInfo info = (ModbusSerialInfo) ModbusCommSettings.parse(entryData);
        // 缺省与 serial 库 schema 预填同源（写端预填的就是这个值，读端兜底不分叉）
        assertEquals(com.ecat.integration.SerialIntegration.Const.READ_TIMEOUT_MS.intValue(),
                info.getTimeout());
    }

    // ==================== TCP ====================

    @Test
    public void tcp_fullShape_mapsFrameFormatAndTimeout() {
        Map<String, Object> comm = new LinkedHashMap<>();
        comm.put("tcp_protocol", "RTU_OVER_TCP");
        comm.put("ip_address", "192.168.1.100");
        comm.put("port", 503);
        comm.put("slave_id", 2);
        comm.put("timeout", 3000);
        Map<String, Object> entryData = new HashMap<>();
        entryData.put("modbus_protocol", "TCP");
        entryData.put("comm_settings", comm);

        ModbusInfo info = ModbusCommSettings.parse(entryData);
        assertTrue(info instanceof ModbusTcpInfo);
        ModbusTcpInfo tcpInfo = (ModbusTcpInfo) info;
        assertEquals(ModbusProtocol.RTU_OVER_TCP, tcpInfo.getProtocol());
        assertEquals("192.168.1.100", tcpInfo.getIpAddress());
        assertEquals(503, tcpInfo.getPort().intValue());
        assertEquals(2, tcpInfo.getSlaveId().intValue());
        assertEquals(3000, tcpInfo.getTimeout().intValue());
    }

    @Test
    public void tcp_missingOptionalFields_fallsBackToLibraryStandard() {
        Map<String, Object> comm = new LinkedHashMap<>();
        comm.put("ip_address", "10.0.0.1");
        comm.put("port", 502);
        comm.put("slave_id", 1);
        Map<String, Object> entryData = new HashMap<>();
        entryData.put("modbus_protocol", "TCP");
        entryData.put("comm_settings", comm);

        ModbusTcpInfo info = (ModbusTcpInfo) ModbusCommSettings.parse(entryData);
        assertEquals("缺 tcp_protocol 默认标准 TCP 帧", ModbusProtocol.TCP, info.getProtocol());
        assertEquals("缺 timeout 默认与 modbus 库 schema 预填同源",
                Const.DEFAULT_TCP_TIMEOUT_MS.intValue(), info.getTimeout().intValue());
    }

    // ==================== 数值解析 ====================

    @Test
    public void stringNumbers_tolerateFloatStrings_fromApiConsumers() {
        Map<String, Object> serial = new LinkedHashMap<>();
        serial.put("serial_port", "/dev/ttyUSB1");
        serial.put("baudrate", "9600.0");      // schema defaultValue 数值型经 API 往返字符串化
        serial.put("data_bits", "8");
        serial.put("stop_bits", "1");
        serial.put("parity", "Odd");
        Map<String, Object> comm = new LinkedHashMap<>();
        comm.put("serial_settings", serial);
        comm.put("slave_id", "2.0");
        Map<String, Object> entryData = new HashMap<>();
        entryData.put("modbus_protocol", "RTU");
        entryData.put("comm_settings", comm);

        ModbusSerialInfo info = (ModbusSerialInfo) ModbusCommSettings.parse(entryData);
        assertEquals(9600, info.getBaudrate());
        assertEquals(8, info.getDataBits());
        assertEquals(2, info.getSlaveId().intValue());
        assertEquals(ModbusSerialInfo.ODD_PARITY, info.getParity());
    }

    // ==================== 严格模式：缺失/未知值抛带字段名的异常 ====================

    @Test
    public void missingTopLevelProtocol_throwsWithFieldName() {
        Map<String, Object> entryData = new HashMap<>();
        entryData.put("comm_settings", new HashMap<>());
        try {
            ModbusCommSettings.parse(entryData);
            fail("缺 modbus_protocol 应抛");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("modbus_protocol"));
        }
    }

    @Test
    public void missingCommSettings_throws() {
        Map<String, Object> entryData = new HashMap<>();
        entryData.put("modbus_protocol", "RTU");
        try {
            ModbusCommSettings.parse(entryData);
            fail("缺 comm_settings 应抛");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("comm_settings"));
        }
    }

    @Test
    public void rtuMissingSerialPort_throwsWithFieldName() {
        Map<String, Object> serial = new LinkedHashMap<>();
        serial.put("baudrate", 9600);
        serial.put("data_bits", 8);
        serial.put("stop_bits", "1");
        serial.put("parity", "None");
        Map<String, Object> comm = new LinkedHashMap<>();
        comm.put("serial_settings", serial);
        comm.put("slave_id", 1);
        Map<String, Object> entryData = new HashMap<>();
        entryData.put("modbus_protocol", "RTU");
        entryData.put("comm_settings", comm);

        try {
            ModbusCommSettings.parse(entryData);
            fail("缺 serial_port 应抛");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("serial_port"));
        }
    }

    @Test
    public void unknownParity_throwsStrictly_noSilentFallback() {
        Map<String, Object> entryData = rtuEntry("parity", "WHATEVER");
        try {
            ModbusCommSettings.parse(entryData);
            fail("未知校验位应严格抛（fail-loud 优于静默兜底 NONE）");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("parity"));
        }
    }

    @Test
    public void unknownStopBits_throwsStrictly() {
        Map<String, Object> entryData = rtuEntry("stop_bits", "1.5");
        try {
            ModbusCommSettings.parse(entryData);
            fail("未知停止位应严格抛");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("stop_bits"));
        }
    }

    @Test
    public void unknownTcpProtocol_throwsWithFieldName() {
        Map<String, Object> comm = new LinkedHashMap<>();
        comm.put("tcp_protocol", "RTU");
        comm.put("ip_address", "10.0.0.1");
        comm.put("port", 502);
        comm.put("slave_id", 1);
        Map<String, Object> entryData = new HashMap<>();
        entryData.put("modbus_protocol", "TCP");
        entryData.put("comm_settings", comm);

        try {
            ModbusCommSettings.parse(entryData);
            fail("非法帧格式应严格抛");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("tcp_protocol"));
        }
    }

    @Test
    public void nonNumericPort_throws() {
        Map<String, Object> comm = new LinkedHashMap<>();
        comm.put("ip_address", "10.0.0.1");
        comm.put("port", "abc");
        comm.put("slave_id", 1);
        Map<String, Object> entryData = new HashMap<>();
        entryData.put("modbus_protocol", "TCP");
        entryData.put("comm_settings", comm);

        try {
            ModbusCommSettings.parse(entryData);
            fail("非数值 port 应抛");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("port"));
        }
    }

    // ==================== 辅助 ====================

    /** 构造最小合法 RTU entry，serial 块可注入单个覆盖字段（parity/stop_bits 等）。 */
    private static Map<String, Object> rtuEntry(String overrideKey, Object overrideValue) {
        Map<String, Object> serial = new LinkedHashMap<>();
        serial.put("serial_port", "/dev/ttyUSB0");
        serial.put("baudrate", 9600);
        serial.put("data_bits", 8);
        serial.put("stop_bits", "1");
        serial.put("parity", "None");
        serial.put(overrideKey, overrideValue);
        Map<String, Object> comm = new LinkedHashMap<>();
        comm.put("serial_settings", serial);
        comm.put("slave_id", 1);
        Map<String, Object> entryData = new HashMap<>();
        entryData.put("modbus_protocol", "RTU");
        entryData.put("comm_settings", comm);
        return entryData;
    }
}
