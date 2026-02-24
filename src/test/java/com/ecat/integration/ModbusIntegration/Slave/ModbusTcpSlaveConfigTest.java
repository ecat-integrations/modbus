/*
 * Copyright (c) 2026 ECAT Team
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ecat.integration.ModbusIntegration.Slave;

import org.junit.*;

import static org.junit.Assert.*;

public class ModbusTcpSlaveConfigTest {

    @Test
    public void testConstructor_DefaultProtocol() {
        ModbusTcpSlaveConfig config = new ModbusTcpSlaveConfig(1, "0.0.0.0", 5020);

        assertEquals(1, config.getSlaveId());
        assertEquals("0.0.0.0", config.getIpAddress());
        assertEquals(5020, config.getPort());
        assertEquals(com.ecat.integration.ModbusIntegration.ModbusProtocol.TCP, config.getProtocol());
    }

    @Test
    public void testConstructor_RtuOverTcp() {
        ModbusTcpSlaveConfig config = new ModbusTcpSlaveConfig(1, "0.0.0.0", 5020,
            com.ecat.integration.ModbusIntegration.ModbusProtocol.RTU_OVER_TCP);

        assertEquals(com.ecat.integration.ModbusIntegration.ModbusProtocol.RTU_OVER_TCP, config.getProtocol());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_InvalidProtocol() {
        new ModbusTcpSlaveConfig(1, "0.0.0.0", 5020,
            com.ecat.integration.ModbusIntegration.ModbusProtocol.SERIAL);
    }

    @Test
    public void testGetConnectionIdentity() {
        ModbusTcpSlaveConfig config = new ModbusTcpSlaveConfig(1, "192.168.1.100", 502);

        assertEquals("192.168.1.100:502", config.getConnectionIdentity());
    }

    @Test
    public void testSetCallback() {
        ModbusTcpSlaveConfig config = new ModbusTcpSlaveConfig(1, "0.0.0.0", 5020);
        ModbusDataCallback callback = new AbstractModbusDataCallback() {};

        config.setCallback(callback);

        assertEquals(callback, config.getCallback());
    }

    @Test
    public void testToString() {
        ModbusTcpSlaveConfig config = new ModbusTcpSlaveConfig(1, "0.0.0.0", 5020);
        String str = config.toString();

        assertTrue(str.contains("0.0.0.0"));
        assertTrue(str.contains("5020"));
        assertTrue(str.contains("slaveId=1"));
    }
}
