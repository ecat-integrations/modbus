package com.ecat.integration.ModbusIntegration;

import com.ecat.core.Integration.IntegrationBase;
import com.ecat.core.Utils.DynamicConfig.ConfigDefinition;
import com.ecat.core.Utils.DynamicConfig.ConfigItem;
import com.ecat.core.Utils.DynamicConfig.ConfigItemBuilder;
import com.ecat.core.Utils.DynamicConfig.IntegerValidator;
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
    private final Map<String, ModbusSource> tcpSources = new HashMap<>(); // TCP资源池
    private final Map<String, ModbusSource> serialSources = new HashMap<>();// 串行资源池

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
    }

    @Override
    public void onStart() {
    }

    @Override
    public void onPause() {
    }

    @Override
    public void onRelease() {
        // 暂停所有资源
        tcpSources.values().forEach(ModbusSource::closeModbus);
        serialSources.values().forEach(ModbusSource::closeModbus);
        // 释放所有资源
        tcpSources.clear();
        serialSources.clear();
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
        Map<String, ModbusSource> sourcePool = getSourcePool(info);
        
        ModbusSource sharedSource = sourcePool.computeIfAbsent(connectionIdentity, 
            k -> new ModbusSource(info, maxWaiters, waitTimeoutMs));
        
        // 统一返回设备特定的DeviceSpecificModbusSource
        DeviceSpecificModbusSource deviceSource = new DeviceSpecificModbusSource(sharedSource, info);
        return deviceSource;
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
    
    private Map<String, ModbusSource> getSourcePool(ModbusInfo info) {
        if (info instanceof ModbusTcpInfo) {
            return tcpSources;
        } else if (info instanceof ModbusSerialInfo) {
            return serialSources;
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
}
