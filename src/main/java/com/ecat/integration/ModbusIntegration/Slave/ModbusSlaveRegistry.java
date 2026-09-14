/*
 * Copyright (c) 2026 ECAT Team
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ecat.integration.ModbusIntegration.Slave;

import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import com.ecat.integration.SerialIntegration.SerialSource;
import com.serotonin.modbus4j.exception.ModbusInitException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Modbus Slave 服务注册管理中心（内部组件）
 *
 * <p>
 * 提供统一的 Slave 服务注册、启动、停止和管理功能。支持同一应用程序
 * 运行多个 Modbus Slave 服务实例（不同连接或不同 SlaveId）。
 * </p>
 *
 * <p>
 * <b>注意：本类为 ModbusIntegration 内部组件，外部集成不应直接使用。</b>
 * 外部集成应通过 {@link com.ecat.integration.ModbusIntegration.ModbusIntegration#registerSlave(
 * com.ecat.integration.ModbusIntegration.Slave.ModbusSlaveConfig)} 注册 Slave 服务，
 * 由 ModbusIntegration 统一管理 SerialSource 等底层资源的获取和生命周期。
 * </p>
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
 * 外部集成使用示例（通过 ModbusIntegration）：
 * <pre>{@code
 * ModbusIntegration modbusIntegration = (ModbusIntegration) core
 *     .getIntegrationRegistry().getIntegration("integration-modbus");
 *
 * // 注册 TCP Slave
 * ModbusTcpSlaveConfig config = new ModbusTcpSlaveConfig(1, "0.0.0.0", 5020);
 * config.setCallback(myCallback);
 * modbusIntegration.registerSlave(config);
 * modbusIntegration.startSlave(config.getConnectionIdentity(), config.getSlaveId());
 *
 * // 停止 Slave
 * modbusIntegration.stopSlave(config.getConnectionIdentity(), config.getSlaveId());
 * modbusIntegration.unregisterSlave(config.getConnectionIdentity(), config.getSlaveId());
 * }</pre>
 *
 * @author coffee
 * @see ModbusSlaveServer
 * @see ModbusSlaveConfig
 * @see com.ecat.integration.ModbusIntegration.ModbusIntegration
 */
public class ModbusSlaveRegistry {
    private final Log log = LogFactory.getLogger(getClass());
    private final Map<String, ModbusSlaveServer> serverMap = new ConcurrentHashMap<>();

    /**
     * 注册 Slave 回调；串口视图经 acquirer 惰性获取。
     *
     * <p><b>获取时机 = server 首建</b>（bug-record-20260913-124500 缺陷A）：先探测同连接
     * 既有 server，命中则直接挂回调不触发获取（同连接重复注册不再多占串口账）；未命中才
     * 调 acquirer（RTU 形态=serial register 获取视图，TCP 形态=返回 null）。
     *
     * <p><b>并发同键竞态</b>：入表用 putIfAbsent 而非 computeIfAbsent——lambda 语义放不下
     * 「获取后回滚」；败者把刚获取的串口视图即取即还（closePort 自清理），胜者 server
     * 承载双方回调，账面不留双份。
     *
     * @param config         Slave 配置（connectionId 键控 server）
     * @param serialAcquirer 串口视图获取器（仅 server 首建时调用一次；TCP 传 {@code () -> null}）
     */
    public void register(ModbusSlaveConfig config, Supplier<SerialSource> serialAcquirer) {
        String connectionId = config.getConnectionIdentity();
        int slaveId = config.getSlaveId();
        ModbusDataCallback callback = config.getCallback();

        if (callback == null) {
            throw new IllegalArgumentException("Callback cannot be null");
        }

        ModbusSlaveServer server = serverMap.get(connectionId);
        if (server == null) {
            SerialSource acquired = serialAcquirer.get();
            ModbusSlaveServer created = new ModbusSlaveServer(config, acquired);
            ModbusSlaveServer winner = serverMap.putIfAbsent(connectionId, created);
            if (winner == null) {
                server = created;
            } else {
                // 并发败者：刚获取的串口视图无人承载，即取即还，不留在账上
                if (acquired != null) {
                    acquired.closePort();
                }
                server = winner;
            }
        }

        server.registerCallback(slaveId, callback);
        log.info("Registered slave callback: connectionId=" + connectionId + ", slaveId=" + slaveId);
    }

    public void unregister(String connectionId, int slaveId) {
        ModbusSlaveServer server = serverMap.get(connectionId);
        if (server == null) {
            log.warn("Server not found for connectionId: " + connectionId);
            return;
        }

        server.unregisterCallback(slaveId);

        // 当 server 无任何 callback 时，停止并从 registry 移除，
        // 避免 register 时复用已停止的 stale server，
        // 同时防止未调 stopSlave 就 unregister 导致的 SerialSource/线程泄漏
        if (server.getCallbackCount() == 0) {
            if (server.isRunning()) {
                server.stop();
                log.info("Stopped slave server during unregister: connectionId=" + connectionId);
            }
            // 摘空拆 server 无条件释放串口（缺陷B）：never-start/启动失败的 server 不走
            // stop()（!running 提前返回），closePort 幂等——running 路径 stop 内已释放，
            // 此处再调为空操作；释放与「server 拆除」对齐，不与 running 绑定
            server.releaseSerialPort();
            serverMap.remove(connectionId);
            log.info("Removed slave server from registry: connectionId=" + connectionId);
        }

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
            log.warn("Server not found for connectionId: " + connectionId);
            return;
        }
        server.stop();
        log.info("Stopped slave server: connectionId=" + connectionId);
    }

    public void stopAll() {
        for (ModbusSlaveServer server : serverMap.values()) {
            server.stop();
            // stop() 对从未 start 的 server 因 !running 提前返回、不碰串口；停机路径与
            // unregister 摘空拆 server 同款无条件释放（幂等，已 stop 关过的此处空转）
            server.releaseSerialPort();
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
