/*
 * Copyright (c) 2026 ECAT Team
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ecat.integration.ModbusIntegration.Slave;

import org.junit.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ModbusSlaveRegistryTest {

    private ModbusSlaveRegistry registry;

    @Before
    public void setUp() {
        registry = new ModbusSlaveRegistry();
    }

    @After
    public void tearDown() {
        registry.clear();
    }

    @Test
    public void testRegister() {
        ModbusTcpSlaveConfig config = new ModbusTcpSlaveConfig(1, "0.0.0.0", 5020);
        ModbusDataCallback callback = mock(ModbusDataCallback.class);
        config.setCallback(callback);

        registry.register(config);

        assertNotNull(registry.getServer("0.0.0.0:5020"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRegister_NullCallback() {
        ModbusTcpSlaveConfig config = new ModbusTcpSlaveConfig(1, "0.0.0.0", 5020);
        config.setCallback(null);

        registry.register(config);
    }

    @Test
    public void testRegister_SameConnectionMultipleSlaveIds() {
        ModbusDataCallback callback1 = mock(ModbusDataCallback.class);
        ModbusDataCallback callback2 = mock(ModbusDataCallback.class);

        ModbusTcpSlaveConfig config1 = new ModbusTcpSlaveConfig(1, "0.0.0.0", 5020);
        config1.setCallback(callback1);
        ModbusTcpSlaveConfig config2 = new ModbusTcpSlaveConfig(2, "0.0.0.0", 5020);
        config2.setCallback(callback2);

        registry.register(config1);
        registry.register(config2);

        assertNotNull(registry.getServer("0.0.0.0:5020"));
    }

    @Test
    public void testUnregister() {
        ModbusTcpSlaveConfig config = new ModbusTcpSlaveConfig(1, "0.0.0.0", 5020);
        ModbusDataCallback callback = mock(ModbusDataCallback.class);
        config.setCallback(callback);

        registry.register(config);
        registry.unregister("0.0.0.0:5020", 1);
    }

    @Test
    public void testIsRunning_NotRunning() {
        assertFalse(registry.isRunning("nonexistent"));
    }

    @Test
    public void testGetServer_NotFound() {
        assertNull(registry.getServer("nonexistent"));
    }

    @Test
    public void testClear() {
        ModbusTcpSlaveConfig config = new ModbusTcpSlaveConfig(1, "0.0.0.0", 5020);
        ModbusDataCallback callback = mock(ModbusDataCallback.class);
        config.setCallback(callback);

        registry.register(config);
        registry.clear();

        assertNull(registry.getServer("0.0.0.0:5020"));
    }
}
