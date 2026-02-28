/*
 * Copyright (c) 2026 ECAT Team
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ecat.integration.ModbusIntegration.Slave;

import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import com.ecat.integration.ModbusIntegration.ModbusProtocol;
import com.ecat.integration.ModbusIntegration.ModbusSerialInfo;
import com.ecat.integration.ModbusIntegration.ModbusSerialPortWrapper;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusSlaveSet;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.ip.tcp.TcpSlave;
import com.serotonin.modbus4j.serial.SerialPortWrapper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Modbus Slave 服务主类
 * 
 * <p>
 * 提供完整的 Modbus Slave 服务实现，支持 TCP 和 Serial RTU 两种传输模式。
 * 作为 Modbus 从站，响应外部 Master 的读写请求。
 * 
 * <p>
 * 核心功能：
 * <ul>
 * <li>支持 TCP 和 Serial RTU 协议</li>
 * <li>支持同一连接上的多个 SlaveId</li>
 * <li>异步启动机制（start() 不阻塞）</li>
 * <li>生命周期管理（start/stop）</li>
 * </ul>
 * 
 * <p>
 * 使用示例：
 * <pre>{@code
 * ModbusSlaveServer server = new ModbusSlaveServer(config);
 * server.registerCallback(1, callback);
 * server.start();
 * // ... 服务运行中 ...
 * server.stop();
 * }</pre>
 * 
 * <p>
 * 技术说明：
 * <ul>
 * <li>TCP Slave 使用 Modbus4J 的 TcpSlave</li>
 * <li>Serial Slave 使用 Modbus4J 的 RtuSlave</li>
 * <li>由于 Modbus4J 的 start() 会阻塞，使用后台线程启动服务</li>
 * </ul>
 * 
 * @author coffee
 * @see ModbusSlaveRegistry
 * @see ModbusSlaveConfig
 * @see CallbackProcessImage
 */
public class ModbusSlaveServer {
    private final Log log = LogFactory.getLogger(getClass());
    private final ModbusSlaveConfig config;
    private ModbusSlaveSet slaveSet;
    private final Map<Integer, CallbackProcessImage> processImageMap = new ConcurrentHashMap<>();
    private volatile boolean running = false;
    private ExecutorService executor;

    public ModbusSlaveServer(ModbusSlaveConfig config) {
        this.config = config;
    }

    public void registerCallback(int slaveId, ModbusDataCallback callback) {
        CallbackProcessImage processImage = processImageMap.computeIfAbsent(slaveId, 
            id -> new CallbackProcessImage(id, callback));
        processImage.setCallback(callback);
    }

    public void unregisterCallback(int slaveId) {
        CallbackProcessImage processImage = processImageMap.remove(slaveId);
        if (processImage != null && slaveSet != null) {
            slaveSet.removeProcessImage(slaveId);
        }
    }

    public synchronized void start() throws ModbusInitException {
        if (running) {
            log.warn("Slave server is already running: " + config.getConnectionIdentity());
            return;
        }

        log.info("Creating slave for config: " + config);
        
        if (config instanceof ModbusTcpSlaveConfig) {
            startTcpSlave((ModbusTcpSlaveConfig) config);
        } else if (config instanceof ModbusSerialSlaveConfig) {
            startSerialSlave((ModbusSerialSlaveConfig) config);
        } else {
            throw new IllegalArgumentException("Unsupported config type: " + config.getClass());
        }

        log.info("Adding " + processImageMap.size() + " process images");
        for (Map.Entry<Integer, CallbackProcessImage> entry : processImageMap.entrySet()) {
            slaveSet.addProcessImage(entry.getValue());
            log.info("Added process image for slaveId: " + entry.getKey());
        }

        running = true;
        executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try {
                log.info("Starting slave server in background thread...");
                slaveSet.start();
            } catch (ModbusInitException e) {
                log.error("Failed to start slave server: " + e.getMessage());
                running = false;
            }
        });

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.info("Modbus Slave server started: " + config);
    }

    private void startTcpSlave(ModbusTcpSlaveConfig tcpConfig) {
        boolean encapsulated = (tcpConfig.getProtocol() == ModbusProtocol.RTU_OVER_TCP);
        slaveSet = new TcpSlave(tcpConfig.getPort(), encapsulated);
    }

    private void startSerialSlave(ModbusSerialSlaveConfig serialConfig) {
        ModbusFactory factory = new ModbusFactory();
        ModbusSerialInfo serialInfo = new ModbusSerialInfo(
            serialConfig.getPortName(),
            serialConfig.getBaudRate(),
            serialConfig.getDataBits(),
            serialConfig.getStopBits(),
            serialConfig.getParity(),
            1000,
            serialConfig.getSlaveId()
        );
        SerialPortWrapper portWrapper = new ModbusSerialPortWrapper(serialInfo);
        slaveSet = factory.createRtuSlave(portWrapper);
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        
        running = false;
        
        if (slaveSet != null) {
            try {
                slaveSet.stop();
                log.info("Modbus Slave server stopped: " + config);
            } catch (Exception e) {
                log.warn("Error stopping slave server: " + e.getMessage());
            }
            slaveSet = null;
        }

        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public String getConnectionIdentity() {
        return config.getConnectionIdentity();
    }

    public ModbusSlaveConfig getConfig() {
        return config;
    }
}
