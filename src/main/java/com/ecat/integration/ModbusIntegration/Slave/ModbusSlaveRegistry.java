/*
 * Copyright (c) 2026 ECAT Team
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ecat.integration.ModbusIntegration.Slave;

import com.serotonin.modbus4j.exception.ModbusInitException;
import lombok.extern.java.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modbus Slave 服务注册管理中心
 * 
 * <p>
 * 提供统一的 Slave 服务注册、启动、停止和管理功能。支持同一应用程序
 * 运行多个 Modbus Slave 服务实例（不同连接或不同 SlaveId）。
 * 
 * <p>
 * 连接标识规则：
 * <ul>
 * <li>TCP: "ipAddress:port"（如 "0.0.0.0:5020"）</li>
 * <li>Serial: "portName"（如 "/dev/ttyUSB0"）</li>
 * </ul>
 * 
 * <p>
 * 两级索引结构：
 * <pre>
 * Registry
 * ├── connectionId ("192.168.1.1:502" 或 "/dev/ttyUSB0")
 * │   └── ModbusSlaveServer 实例
 * │       └── processImageMap
 * │           ├── slaveId=1 → CallbackProcessImage → ModbusDataCallback
 * │           └── slaveId=2 → CallbackProcessImage → ModbusDataCallback
 * </pre>
 * 
 * <p>
 * 使用示例：
 * <pre>{@code
 * ModbusSlaveRegistry registry = new ModbusSlaveRegistry();
 * 
 * // 注册 TCP Slave
 * ModbusTcpSlaveConfig config = new ModbusTcpSlaveConfig(1, "0.0.0.0", 5020);
 * config.setCallback(myCallback);
 * registry.register(config);
 * registry.start(config.getConnectionIdentity(), config.getSlaveId());
 * 
 * // 停止所有服务
 * registry.stopAll();
 * }</pre>
 * 
 * @author coffee
 * @see ModbusSlaveServer
 * @see ModbusSlaveConfig
 */
@Log
public class ModbusSlaveRegistry {
    private final Map<String, ModbusSlaveServer> serverMap = new ConcurrentHashMap<>();

    public void register(ModbusSlaveConfig config) {
        String connectionId = config.getConnectionIdentity();
        int slaveId = config.getSlaveId();
        ModbusDataCallback callback = config.getCallback();

        if (callback == null) {
            throw new IllegalArgumentException("Callback cannot be null");
        }

        ModbusSlaveServer server = serverMap.computeIfAbsent(connectionId, 
            id -> new ModbusSlaveServer(config));

        server.registerCallback(slaveId, callback);
        log.info("Registered slave callback: connectionId=" + connectionId + ", slaveId=" + slaveId);
    }

    public void unregister(String connectionId, int slaveId) {
        ModbusSlaveServer server = serverMap.get(connectionId);
        if (server == null) {
            log.warning("Server not found for connectionId: " + connectionId);
            return;
        }

        server.unregisterCallback(slaveId);
        log.info("Unregistered slave callback: connectionId=" + connectionId + ", slaveId=" + slaveId);
    }

    public void start(String connectionId, int slaveId) throws ModbusInitException {
        ModbusSlaveServer server = serverMap.get(connectionId);
        if (server == null) {
            throw new IllegalArgumentException("Server not found for connectionId: " + connectionId);
        }
        server.start();
        log.info("Started slave server: connectionId=" + connectionId);
    }

    public void stop(String connectionId, int slaveId) {
        ModbusSlaveServer server = serverMap.get(connectionId);
        if (server == null) {
            log.warning("Server not found for connectionId: " + connectionId);
            return;
        }
        server.stop();
        log.info("Stopped slave server: connectionId=" + connectionId);
    }

    public void stopAll() {
        for (ModbusSlaveServer server : serverMap.values()) {
            server.stop();
        }
        log.info("Stopped all slave servers");
    }

    public void clear() {
        stopAll();
        serverMap.clear();
        log.info("Cleared all slave servers");
    }

    public ModbusSlaveServer getServer(String connectionId) {
        return serverMap.get(connectionId);
    }

    public boolean isRunning(String connectionId) {
        ModbusSlaveServer server = serverMap.get(connectionId);
        return server != null && server.isRunning();
    }
}
