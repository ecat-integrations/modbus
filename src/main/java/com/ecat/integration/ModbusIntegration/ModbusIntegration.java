package com.ecat.integration.ModbusIntegration;

import com.ecat.core.Integration.IntegrationBase;
import com.ecat.core.Utils.DynamicConfig.ConfigDefinition;
import com.ecat.core.Utils.DynamicConfig.ConfigItem;
import com.ecat.core.Utils.DynamicConfig.ConfigItemBuilder;
import com.ecat.core.Utils.DynamicConfig.IntegerValidator;
import com.ecat.integration.ModbusIntegration.Slave.ModbusSlaveConfig;
import com.ecat.integration.ModbusIntegration.Slave.ModbusSerialSlaveConfig;
import com.ecat.integration.ModbusIntegration.Slave.ModbusSlaveRegistry;
import com.ecat.integration.SerialIntegration.SerialInfo;
import com.ecat.integration.SerialIntegration.SerialIntegration;
import com.ecat.integration.SerialIntegration.SerialSource;

import java.util.HashMap;
import java.util.Map;

/**
 * Modbus集成管理类，分别管理TCP和串行资源
 * 
 * <p>
 * 主要功能：
 * <ul>
 * <li>初始化、启动、暂停和释放资源</li>
 * <li>注册和获取Modbus资源</li>
 * <li>支持TCP和串行协议</li>
 * </ul>
 * 
 * @author coffee
 */
public class ModbusIntegration extends IntegrationBase {
    private final Map<String, ModbusSource> tcpSources = new HashMap<>();
    private final Map<String, ModbusSource> serialSources = new HashMap<>();
    private final ModbusSlaveRegistry slaveRegistry = new ModbusSlaveRegistry();

    private SerialIntegration serialIntegration;

    protected ConfigDefinition configDefinition;

    protected Integer maxWaiters; // 新建ModbusSource默认最大等待请求数
    protected Integer waitTimeoutMs; //  新建ModbusSource默认等待超时时间

    @Override
    public void onInit() {
        // 初始化逻辑（如加载配置）
        configDefinition = getConfigDefinition();
        Map<String, Object> integrationConfig = integrationManager.loadConfig(this.getName());
        boolean isValid = configDefinition.validateConfig(integrationConfig);
        if(isValid) {
            maxWaiters = (Integer) integrationConfig.getOrDefault("max_waiters", Const.DEFAULT_MAX_WAITERS);
            waitTimeoutMs = (Integer) integrationConfig.getOrDefault("wait_timeout", Const.DEFAULT_WAIT_TIMEOUT_MS);
        }
        else{
            log.error("ModbusIntegration configuration is invalid, using default values.");
            maxWaiters = Const.DEFAULT_MAX_WAITERS;
            waitTimeoutMs = Const.DEFAULT_WAIT_TIMEOUT_MS;
        }
        log.info("ModbusIntegration initialized with maxWaiters: " + maxWaiters + ", waitTimeoutMs: " + waitTimeoutMs);

        // Get serial integration for RTU path
        try {
            serialIntegration = (SerialIntegration) integrationRegistry
                .getIntegration("integration-serial");
            if (serialIntegration != null) {
                log.info("Serial integration found for RTU path");
            } else {
                log.warn("Serial integration not found, RTU path will not be available");
            }
        } catch (Exception e) {
            log.warn("Failed to get serial integration: " + e.getMessage());
        }
    }

    @Override
    public void onStart() {
    }

    @Override
    public void onPause() {
    }

    @Override
    public void onRelease() {
        // 销毁所有共享连接的底层资源（master、executor、serial port）
        // destroyResources() 由 ModbusMasterFactory 统一处理 TCP/RTU 传输资源释放
        tcpSources.values().forEach(source -> source.destroyResources());
        serialSources.values().forEach(source -> source.destroyResources());
        tcpSources.clear();
        serialSources.clear();
        slaveRegistry.clear();
    }

    public ConfigDefinition getConfigDefinition() {
        if (configDefinition == null) {
            configDefinition = new ConfigDefinition();

            // 设置最大等待数验证范围，1-10
            IntegerValidator maxWaitersValidator = new IntegerValidator(1, 10);
            IntegerValidator waitTimeoutValidator = new IntegerValidator(1000, 10000);

            ConfigItemBuilder builder = new ConfigItemBuilder()
                .add(new ConfigItem<>("max_waiters", Integer.class, false, Const.DEFAULT_MAX_WAITERS, maxWaitersValidator))
                .add(new ConfigItem<>("wait_timeout", Integer.class, false, Const.DEFAULT_WAIT_TIMEOUT_MS, waitTimeoutValidator));

            configDefinition.define(builder);
        }
        return configDefinition;
    }

    /**
     * 注册Modbus资源（自动区分协议类型）
     * 
     * @param info     Modbus设备信息（TCP或串行）
     * @param identity 资源标识（如设备编号）
     * @return ModbusSource实例
     */
    public ModbusSource register(ModbusInfo info, String identity) {
        ModbusSource source = createOrGetSource(info, identity);
        source.registerIntegration(identity);
        return source;
    }

    private ModbusSource createOrGetSource(ModbusInfo info, String identity) {
        // 统一处理：获取共享的底层source，然后返回设备特定的包装器
        String connectionIdentity = getConnectionIdentity(info);

        // 清理已销毁的 source（最后一个设备 release 后 executor 已 shutdown，
        // 但 source 仍留在 map 中，导致 computeIfAbsent 返回死 source）
        Map<String, ModbusSource> sourceMap = (info instanceof ModbusSerialInfo) ? serialSources : tcpSources;
        ModbusSource existing = sourceMap.get(connectionIdentity);
        if (existing != null && !existing.isModbusOpen()) {
            sourceMap.remove(connectionIdentity);
        }

        ModbusSource sharedSource;
        if (info instanceof ModbusSerialInfo) {
            // RTU 模式：必须通过 serial integration 管理串口
            if (serialIntegration == null) {
                throw new IllegalStateException("Serial integration is required for RTU devices but is not available");
            }
            sharedSource = serialSources.computeIfAbsent(connectionIdentity, k -> {
                ModbusSerialInfo serialInfo = (ModbusSerialInfo) info;
                SerialSource serialSource = serialIntegration.register(
                    convertToSerialInfo(serialInfo), "modbus-" + connectionIdentity);
                ModbusSource source = new ModbusSource(serialInfo, maxWaiters, waitTimeoutMs, true, false);
                source.initSerialMaster(serialInfo, serialSource);
                return source;
            });
        } else {
            // TCP 模式
            sharedSource = tcpSources.computeIfAbsent(connectionIdentity,
                k -> new ModbusSource(info, maxWaiters, waitTimeoutMs));
        }

        // 统一返回设备特定的DeviceSpecificModbusSource（传入 identity 用于正确的 close/release）
        return new DeviceSpecificModbusSource(sharedSource, info, identity);
    }

    private SerialInfo convertToSerialInfo(ModbusSerialInfo info) {
        return new SerialInfo(
            info.getPortName(), info.getBaudrate(), info.getDataBits(),
            info.getStopBits(), info.getParity(), 0, info.getTimeout());
    }
    
    private String getConnectionIdentity(ModbusInfo info) {
        if (info instanceof ModbusTcpInfo) {
            return ((ModbusTcpInfo) info).getIpAddress() + ":" + ((ModbusTcpInfo) info).getPort();
        } else if (info instanceof ModbusSerialInfo) {
            return ((ModbusSerialInfo) info).getPortName();
        } else {
            throw new IllegalArgumentException("不支持的Modbus协议类型");
        }
    }
    
    /**
     * 获取TCP资源
     * 
     * @param identity 资源标识
     * @return TCP类型的ModbusSource
     */
    public ModbusSource getTcpSource(String identity) {
        return tcpSources.get(identity);
    }

    /**
     * 获取串行资源
     *
     * @param identity 资源标识
     * @return 串行类型的ModbusSource
     */
    public ModbusSource getSerialSource(String identity) {
        return serialSources.get(identity);
    }

    // ==================== Slave API ====================

    /**
     * 注册 Slave 服务
     *
     * @param config Slave 配置
     */
    public void registerSlave(ModbusSlaveConfig config) {
        SerialSource serialSource = null;
        if (config instanceof ModbusSerialSlaveConfig) {
            // RTU 模式：从 serial integration 获取 SerialSource
            if (serialIntegration == null) {
                throw new IllegalStateException("Serial integration is required for RTU Slave but is not available");
            }
            ModbusSerialSlaveConfig serialConfig = (ModbusSerialSlaveConfig) config;
            com.ecat.integration.SerialIntegration.SerialInfo serialInfo = new com.ecat.integration.SerialIntegration.SerialInfo(
                serialConfig.getPortName(), serialConfig.getBaudRate(), serialConfig.getDataBits(),
                serialConfig.getStopBits(), serialConfig.getParity(), 0, 1000
            );
            serialSource = serialIntegration.register(serialInfo, "modbus-slave-" + config.getConnectionIdentity());
        }
        slaveRegistry.register(config, serialSource);
    }

    /**
     * 注销 Slave 服务
     * 
     * @param connectionId 连接标识
     * @param slaveId 从站ID
     */
    public void unregisterSlave(String connectionId, int slaveId) {
        slaveRegistry.unregister(connectionId, slaveId);
    }

    /**
     * 启动 Slave 服务
     * 
     * @param connectionId 连接标识
     * @param slaveId 从站ID
     */
    public void startSlave(String connectionId, int slaveId) {
        try {
            slaveRegistry.start(connectionId, slaveId);
        } catch (Exception e) {
            log.error("Failed to start slave: {}", e.getMessage());
            throw new RuntimeException("Failed to start slave", e);
        }
    }

    /**
     * 停止 Slave 服务
     * 
     * @param connectionId 连接标识
     * @param slaveId 从站ID
     */
    public void stopSlave(String connectionId, int slaveId) {
        slaveRegistry.stop(connectionId, slaveId);
    }

    /**
     * 检查 Slave 服务是否运行中
     * 
     * @param connectionId 连接标识
     * @return 是否运行中
     */
    public boolean isSlaveRunning(String connectionId) {
        return slaveRegistry.isRunning(connectionId);
    }
}
