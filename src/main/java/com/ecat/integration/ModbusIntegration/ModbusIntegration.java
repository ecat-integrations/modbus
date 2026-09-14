package com.ecat.integration.ModbusIntegration;

import com.ecat.core.CommTrace.OwnerLevel;
import com.ecat.core.CommTrace.ResourceKind;
import com.ecat.core.CommTrace.ResourceOwner;
import com.ecat.core.CommTrace.ResourceQuery;
import com.ecat.core.CommTrace.ResourceRef;
import com.ecat.core.Device.RemovalHost;
import com.ecat.core.Integration.IntegrationBase;
import com.ecat.core.Utils.DynamicConfig.ConfigDefinition;
import com.ecat.core.Utils.DynamicConfig.ConfigItem;
import com.ecat.core.Utils.DynamicConfig.ConfigItemBuilder;
import com.ecat.core.Utils.DynamicConfig.IntegerValidator;
import com.ecat.integration.ModbusIntegration.Sdk.ModbusSdkTimers;
import com.ecat.integration.ModbusIntegration.Slave.ModbusSlaveConfig;
import com.ecat.integration.ModbusIntegration.Slave.ModbusSerialSlaveConfig;
import com.ecat.integration.ModbusIntegration.Slave.ModbusSlaveRegistry;
import com.ecat.integration.SerialIntegration.SerialInfo;
import com.ecat.integration.SerialIntegration.SerialIntegration;
import com.ecat.integration.SerialIntegration.SerialSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

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
public class ModbusIntegration extends IntegrationBase implements ResourceQuery<ModbusInfo, ModbusSource> {
    // 连接键 → 共享源地图（tcpSources=ip:port / serialSources=portName）：owner 化新增
    // findSourceByOwner 全量迭代读面，与 register 的 computeIfAbsent 并发建源互踩——
    // ConcurrentHashMap 化（弱一致迭代；computeIfAbsent 既有用法语义不变，同键并发
    // 注册仍只建一个源对象）。本集成全程无锁（无 synchronized/ReentrantLock），不存在
    // [锁→地图写] 反向序与 map bin 锁成环的路径。
    private final Map<String, ModbusSource> tcpSources = new ConcurrentHashMap<>();
    private final Map<String, ModbusSource> serialSources = new ConcurrentHashMap<>();
    private final ModbusSlaveRegistry slaveRegistry = new ModbusSlaveRegistry();

    private SerialIntegration serialIntegration;

    protected ConfigDefinition configDefinition;

    protected Integer maxWaiters; // 新建ModbusSource默认最大等待请求数
    protected Integer waitTimeoutMs; //  新建ModbusSource默认等待超时时间
    /** modbus IO 旁池线程数（R3 期 4，15 号 §6.4 D4）：阻塞 send 的专职有界池定容。 */
    protected Integer ioPoolSize;

    @Override
    public void onInit() {
        // 初始化逻辑（如加载配置）
        configDefinition = getConfigDefinition();
        Map<String, Object> integrationConfig = integrationManager.loadConfig(this.getName());
        boolean isValid = configDefinition.validateConfig(integrationConfig);
        if(isValid) {
            maxWaiters = (Integer) integrationConfig.getOrDefault("max_waiters", Const.DEFAULT_MAX_WAITERS);
            waitTimeoutMs = (Integer) integrationConfig.getOrDefault("wait_timeout", Const.DEFAULT_WAIT_TIMEOUT_MS);
            ioPoolSize = (Integer) integrationConfig.getOrDefault("io_pool_size", Const.DEFAULT_IO_POOL_SIZE);
        }
        else{
            log.error("ModbusIntegration configuration is invalid, using default values.");
            maxWaiters = Const.DEFAULT_MAX_WAITERS;
            waitTimeoutMs = Const.DEFAULT_WAIT_TIMEOUT_MS;
            ioPoolSize = Const.DEFAULT_IO_POOL_SIZE;
        }
        // IO 旁池按配置建池（ecat-modbus-io-0..N-1）；读/写事务的阻塞 send 全部迁入（ModbusSource.dispatchIo）
        ModbusIoPool.initialize(ioPoolSize);
        log.info("ModbusIntegration initialized with maxWaiters: " + maxWaiters
                + ", waitTimeoutMs: " + waitTimeoutMs + ", ioPoolSize: " + ioPoolSize);

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
    protected void onReleaseImpl() {
        // 销毁所有共享连接的底层资源（master、serial port）
        // destroyResources() 由 ModbusMasterFactory 统一处理 TCP/RTU 传输资源释放
        tcpSources.values().forEach(source -> source.destroyResources());
        serialSources.values().forEach(source -> source.destroyResources());
        tcpSources.clear();
        serialSources.clear();
        slaveRegistry.clear();
        // IO 旁池关停（源全毁后再停池：在途 send 自然跑完，此后新提交拒绝）
        ModbusIoPool.shutdown();
        // SDK 定时池最后收口（29 号 v2 S1 域自持调度）：轮询链已先随各宿主 RemovalHost
        // sweep 停止（消费集成先于依赖集成释放），此处兜底强制停待发单发（幂等、终端态
        // ——停机后新提交 REE，与 ModbusIoPool/serial/tcp/http 四域统一，见 ModbusSdkTimers）
        ModbusSdkTimers.shutdown();
    }

    public ConfigDefinition getConfigDefinition() {
        if (configDefinition == null) {
            configDefinition = new ConfigDefinition();

            // 设置最大等待数验证范围，1-10
            IntegerValidator maxWaitersValidator = new IntegerValidator(1, 10);
            IntegerValidator waitTimeoutValidator = new IntegerValidator(1000, 10000);
            // IO 旁池容量 1-64（R3 期 4，15 号 §6.4 D4：默认 16 起步观测调）
            IntegerValidator ioPoolSizeValidator = new IntegerValidator(1, 64);

            ConfigItemBuilder builder = new ConfigItemBuilder()
                .add(new ConfigItem<>("max_waiters", Integer.class, false, Const.DEFAULT_MAX_WAITERS, maxWaitersValidator))
                .add(new ConfigItem<>("wait_timeout", Integer.class, false, Const.DEFAULT_WAIT_TIMEOUT_MS, waitTimeoutValidator))
                .add(new ConfigItem<>("io_pool_size", Integer.class, false, Const.DEFAULT_IO_POOL_SIZE, ioPoolSizeValidator));

            configDefinition.define(builder);
        }
        return configDefinition;
    }

    /**
     * host 收口注册（io-resource-owner §5.3 三合一）：owner 从宿主派生（DeviceBase→DEVICE
     * 层 / IntegrationBase→INTEGRATION 层，不靠调用方手拼串）+ {@code host.onRemove(摘账)}
     * 构造期绑定销毁（core LIFO 统一收割，忘了绑定在签名层面不可能）+ 宿主已终态拒绝并
     * 就地回收（EasyHttpClient 同型守卫，不留半活账目）。设备类调用传 this。
     */
    public ModbusSource register(ModbusInfo info, RemovalHost host) {
        Objects.requireNonNull(host, "host 不能为 null——register(ModbusInfo, host) 从宿主派生归属");
        // 身份校验先于任何资源创建（严格 fail-fast：非身份宿主/设备 entry 缺 entryId 即抛）
        ResourceOwner owner = ResourceOwner.of(host);
        ModbusSource source = register(info, owner);
        try {
            host.onRemove(source::closeModbus);
        } catch (RejectedExecutionException e) {
            // 宿主已终态：就地回收刚建的视图（末源时随销毁 master 一并清理），再原样上抛
            source.closeModbus();
            throw e;
        }
        return source;
    }

    /**
     * 带主注册重载（§5.3）：owner 显式携带。RTU 形态经 asAdapter() 把 owner 原样转发
     * serial（§4 借用带主——同口 N 设备 = serial 账本 N 条 ADAPTER 条目，不再自造
     * "modbus-端口" 中间商）；调用方生命周期自管（closeModbus 摘账，末源销毁连接）。
     */
    public ModbusSource register(ModbusInfo info, ResourceOwner owner) {
        Objects.requireNonNull(owner, "owner 不能为 null——归属身份必填（host 收口重载自动派生）");
        return createOrGetSource(info, owner);
    }

    /**
     * 统一入口：获取共享的底层 source，登记注册方账本，返回设备特定的包装器。
     */
    private ModbusSource createOrGetSource(ModbusInfo info, ResourceOwner owner) {
        String connectionIdentity = getConnectionIdentity(info);

        // 清理已销毁的 source（最后一个设备 release 后 destroyed 已置位（destroyResources），
        // 但 source 仍留在 map 中，导致 computeIfAbsent 返回死 source）
        Map<String, ModbusSource> sourceMap = (info instanceof ModbusSerialInfo) ? serialSources : tcpSources;
        ModbusSource existing = sourceMap.get(connectionIdentity);
        if (existing != null && !existing.isModbusOpen()) {
            sourceMap.remove(connectionIdentity);
            existing = null;
        }

        // TCP 模式：同一 TCP 连接只能使用一种帧格式（MBAP 或 RTU over TCP），
        // 如果已存在连接但协议不匹配，直接拒绝注册，避免帧格式冲突导致通信失败。
        if (existing != null && info instanceof ModbusTcpInfo) {
            ModbusProtocol existingProtocol = existing.getModbusInfo().getProtocol();
            ModbusProtocol newProtocol = info.getProtocol();
            if (existingProtocol != newProtocol) {
                throw new IllegalStateException(String.format(
                    "Modbus TCP 协议冲突: 连接 %s 已被注册为 %s 协议，无法再用 %s 协议注册。"
                        + "同一 IP:Port 只能使用一种帧格式，请检查设备配置或删除冲突的 ConfigEntry。",
                    connectionIdentity, existingProtocol, newProtocol));
            }
        }

        ModbusSource sharedSource;
        // RTU 带主转发（§4）：每设备一条 asAdapter() 串口视图——首设备视图兼作
        // master 传输通道，后续设备视图为 serial 账本挂靠。首设备提前退出不伤 master：
        // master 传输流经 ModbusSerialPortWrapper 直取共享 SerialSourcePort 的
        // getInputStream/getOutputStream（字节通路不经过任何视图对象），视图 closePort 只减
        // serial 引用计数，物理口仅在计数归零时才拆——故先注销者视图早摘，后继设备与
        // master 传输连续性不受影响（运行时连续性由 W6 e2e 同口 RTU 场景覆盖）。
        SerialSource deviceSerialView = null;
        if (info instanceof ModbusSerialInfo) {
            // RTU 模式：必须通过 serial integration 管理串口
            if (serialIntegration == null) {
                throw new IllegalStateException("Serial integration is required for RTU devices but is not available");
            }
            ModbusSerialInfo serialInfo = (ModbusSerialInfo) info;
            deviceSerialView = serialIntegration.register(convertToSerialInfo(serialInfo), owner.asAdapter());
            final SerialSource masterView = deviceSerialView;
            sharedSource = serialSources.computeIfAbsent(connectionIdentity, k -> {
                ModbusSource source = new ModbusSource(serialInfo, maxWaiters, waitTimeoutMs, true, false);
                source.initSerialMaster(serialInfo, masterView);
                return source;
            });
        } else {
            // TCP 模式（阻塞 send 走 IO 旁池；轮询/写的互斥由源锁承担——19 号 v2 S3 写闸塌缩后无引擎车道键路由）
            sharedSource = tcpSources.computeIfAbsent(connectionIdentity,
                k -> new ModbusSource(info, maxWaiters, waitTimeoutMs, false, false));
        }

        // 注册方入账 + 统一返回带 owner 的设备视图（锁注入与 closeModbus 摘账都按 owner
        // 键；RTU 视图随账摘除）
        sharedSource.registerIntegration(owner);
        return new DeviceSpecificModbusSource(sharedSource, info, owner, deviceSerialView);
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

    // ========== ResourceQuery<ModbusInfo, ModbusSource>（io-resource-owner §9-10 精准查询契约） ==========

    /**
     * 按身份匹配遍历账本（TCP/串口两张源地图 × 每源注册方账本）找唯一命中源。
     * 精准一对一语义（§9-10）：未注册如实 null；同一身份命中多笔资源属异常形态，明确抛
     * （不猜不取首笔）。LEGACY 条目层级天然不匹配（level 判据先于 coordinate 解引用，
     * LEGACY 无坐标字段）。RTU 设备在此命中 modbus 共享源（serial 侧另有同 owner 的
     * ADAPTER 条目，§4 借用带主）。
     */
    private ModbusSource findSourceByOwner(OwnerLevel level, String coordinate, String entryId, String deviceId) {
        ModbusSource hit = null;
        for (Map<String, ModbusSource> sourceMap : new Map[]{tcpSources, serialSources}) {
            for (ModbusSource source : sourceMap.values()) {
                for (ResourceOwner owner : source.getRegisteredOwners()) {
                    if (owner.getLevel() != level || !owner.getCoordinate().equals(coordinate)) {
                        continue;
                    }
                    // INTEGRATION 层无 entry 维度；ENTRY/DEVICE 层精确匹配 entryId（+deviceId）
                    if (level != OwnerLevel.INTEGRATION && !owner.getEntryId().equals(entryId)) {
                        continue;
                    }
                    if (level == OwnerLevel.DEVICE && !owner.getDeviceId().equals(deviceId)) {
                        continue;
                    }
                    if (hit != null && hit != source) {
                        throw new IllegalStateException("身份命中多笔资源（异常形态，明确异常不猜）: level="
                                + level + ", coordinate=" + coordinate + ", entryId=" + entryId
                                + ", deviceId=" + deviceId + " 同时命中多笔 modbus 连接");
                    }
                    hit = source;
                }
            }
        }
        return hit;
    }

    @Override
    public ModbusInfo getIntegrationInfo(String coordinate) {
        Objects.requireNonNull(coordinate, "coordinate 不能为 null");
        ModbusSource source = findSourceByOwner(OwnerLevel.INTEGRATION, coordinate, null, null);
        return source != null ? source.getModbusInfo() : null;
    }

    @Override
    public ModbusInfo getEntryInfo(String coordinate, String entryId) {
        Objects.requireNonNull(coordinate, "coordinate 不能为 null");
        Objects.requireNonNull(entryId, "entryId 不能为 null");
        ModbusSource source = findSourceByOwner(OwnerLevel.ENTRY, coordinate, entryId, null);
        return source != null ? source.getModbusInfo() : null;
    }

    @Override
    public ModbusInfo getDeviceInfo(String coordinate, String entryId, String deviceId) {
        Objects.requireNonNull(coordinate, "coordinate 不能为 null");
        Objects.requireNonNull(entryId, "entryId 不能为 null");
        Objects.requireNonNull(deviceId, "deviceId 不能为 null");
        ModbusSource source = findSourceByOwner(OwnerLevel.DEVICE, coordinate, entryId, deviceId);
        return source != null ? source.getModbusInfo() : null;
    }

    /**
     * 消费方一步得源（§9-10）：按设备身份命中其所在共享连接，追加消费方 owner 入账并
     * 绑定宿主收尾。返回视图携带共享连接 Info（连接级参数；消费方按需用带 slaveId 的
     * 读面自行指定从站）。
     */
    @Override
    public ModbusSource registerForDevice(String coordinate, String entryId, String deviceId,
            ResourceOwner owner, RemovalHost host) {
        Objects.requireNonNull(coordinate, "coordinate 不能为 null");
        Objects.requireNonNull(entryId, "entryId 不能为 null");
        Objects.requireNonNull(deviceId, "deviceId 不能为 null");
        Objects.requireNonNull(owner, "owner 不能为 null——消费方自己的注册身份");
        Objects.requireNonNull(host, "host 不能为 null——生命周期锚点");
        ModbusSource shared = findSourceByOwner(OwnerLevel.DEVICE, coordinate, entryId, deviceId);
        if (shared == null) {
            // 未注册如实 null：不凭 Info 另开资源（杜绝查注间隙设备已摘的半死挂靠，§9-10①）
            return null;
        }
        // RTU 形态：消费方也是串口最终使用者之一，同 §4 借用带主挂 ADAPTER 条目
        SerialSource deviceSerialView = null;
        if (shared.getModbusInfo() instanceof ModbusSerialInfo) {
            deviceSerialView = serialIntegration.register(
                convertToSerialInfo((ModbusSerialInfo) shared.getModbusInfo()), owner.asAdapter());
        }
        ModbusSource view = new DeviceSpecificModbusSource(shared, shared.getModbusInfo(), owner, deviceSerialView);
        shared.registerIntegration(owner);
        try {
            host.onRemove(view::closeModbus);
        } catch (RejectedExecutionException e) {
            // 宿主已终态：就地回收（消费方条目摘回；末源时随销毁 master 一并清理），再原样上抛
            view.closeModbus();
            throw e;
        }
        return view;
    }

    /** 反向折叠的源定位：modbus 账本只认 MODBUS_CONNECTION ref，其余属调用方错误。 */
    private ModbusSource sourceOfRef(ResourceRef ref) {
        Objects.requireNonNull(ref, "ref 不能为 null");
        if (ref.getKind() != ResourceKind.MODBUS_CONNECTION) {
            throw new IllegalArgumentException("modbus 账本只认 MODBUS_CONNECTION ref，实为 " + ref.getKind());
        }
        ModbusSource source = tcpSources.get(ref.getKey());
        return source != null ? source : serialSources.get(ref.getKey());
    }

    @Override
    public List<ResourceOwner> getDeviceOwners(ResourceRef ref) {
        ModbusSource source = sourceOfRef(ref);
        Map<String, ResourceOwner> owners = new LinkedHashMap<>();
        if (source != null) {
            for (ResourceOwner owner : source.getRegisteredOwners()) {
                owners.putIfAbsent(owner.ownerKey(), owner);
            }
        }
        return new ArrayList<>(owners.values());
    }

    @Override
    public List<ResourceOwner> getEntryOwners(ResourceRef ref) {
        return foldOwners(ref, owner -> owner.getLevel() == OwnerLevel.DEVICE
                ? ResourceOwner.entry(owner.getCoordinate(), owner.getEntryId()) : owner);
    }

    @Override
    public List<ResourceOwner> getIntegrationOwners(ResourceRef ref) {
        return foldOwners(ref, owner -> owner.getLevel() == OwnerLevel.LEGACY
                ? owner : ResourceOwner.integration(owner.getCoordinate()));
    }

    /** 折叠加去重读面（运维诊断用）：按 ownerKey 去重保序；LEGACY 无层可折原样透传。 */
    private List<ResourceOwner> foldOwners(ResourceRef ref, UnaryOperator<ResourceOwner> fold) {
        ModbusSource source = sourceOfRef(ref);
        Map<String, ResourceOwner> owners = new LinkedHashMap<>();
        if (source != null) {
            for (ResourceOwner registered : source.getRegisteredOwners()) {
                ResourceOwner folded = fold.apply(registered);
                owners.putIfAbsent(folded.ownerKey(), folded);
            }
        }
        return new ArrayList<>(owners.values());
    }

    // ==================== Slave API ====================

    /**
     * 注册 Slave 服务
     *
     * <p>RTU 串口视图惰性获取（bug-record-20260913-124500 缺陷A）：acquirer 只在
     * server 首建时被注册中心调用——同连接重复注册复用既有 server，不再无条件获取
     * 串口（旧形态每次注册都取新视图，复用命中时被静默丢弃泄漏）。
     *
     * @param config Slave 配置
     */
    public void registerSlave(ModbusSlaveConfig config) {
        Supplier<SerialSource> serialAcquirer;
        if (config instanceof ModbusSerialSlaveConfig) {
            // RTU 模式：从 serial integration 获取 SerialSource（首建时才取）
            if (serialIntegration == null) {
                throw new IllegalStateException("Serial integration is required for RTU Slave but is not available");
            }
            ModbusSerialSlaveConfig serialConfig = (ModbusSerialSlaveConfig) config;
            SerialInfo serialInfo = new SerialInfo(
                serialConfig.getPortName(), serialConfig.getBaudRate(), serialConfig.getDataBits(),
                serialConfig.getStopBits(), serialConfig.getParity(), 0, 1000
            );
            // slave 串口归属 = 本集成自身（INTEGRATION 层）经 ADAPTER 带主转发（§4 同型：
            // serial 账本条目标注经 modbus 转手，主身份仍是 integration-modbus）
            serialAcquirer = () -> serialIntegration.register(serialInfo, ResourceOwner.of(this).asAdapter());
        } else {
            // TCP 形态无串口资源，获取器恒 null
            serialAcquirer = () -> null;
        }
        slaveRegistry.register(config, serialAcquirer);
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
