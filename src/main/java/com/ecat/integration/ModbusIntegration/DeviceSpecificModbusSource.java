package com.ecat.integration.ModbusIntegration;

import com.ecat.core.CommTrace.OwnerLevel;
import com.ecat.core.CommTrace.ResourceOwner;
import com.ecat.integration.SerialIntegration.SerialSource;
import com.serotonin.modbus4j.msg.*;
import lombok.Getter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 设备特定的ModbusSource，用于解决多设备共享连接时的slaveId冲突问题
 *
 * 每个DeviceSpecificModbusSource实例对应一个具体的设备（具有特定的slaveId），
 * 但底层共享同一个ModbusMaster和锁机制。
 *
 * @author coffee
 */
public class DeviceSpecificModbusSource extends ModbusSource {

    private final ModbusSource delegate;
    @Getter
    private final ModbusInfo deviceModbusInfo;
    private final String deviceIdentity;
    // 本设备注册 owner（io-resource-owner §5.3 包装器）：锁获取自动注入（集成事务代码
    // 零改动）；LEGACY 包装（旧字符串身份）不注入。null=旧两参/字符串构造形态。
    private final ResourceOwner owner;
    // RTU 带主转发的本设备串口视图（§4 借用带主，serial 账本 ADAPTER 条目）：
    // closeModbus 时随自身账目一并摘除；TCP / LEGACY 中间商形态为 null。
    private final SerialSource deviceSerialSource;

    /**
     * 创建设备特定的ModbusSource
     * @param delegate 共享的底层ModbusSource
     * @param deviceModbusInfo 当前设备的ModbusInfo
     * @param deviceIdentity 设备唯一标识（用于 closeModbus 时的引用计数）
     */
    public DeviceSpecificModbusSource(ModbusSource delegate, ModbusInfo deviceModbusInfo, String deviceIdentity) {
        // 调用父类protected构造函数，skipOpen=true（由 delegate 管理 master），
        // delegateMode=true（不创建自己的 executor，所有操作委托给 delegate）
        super(delegate.getModbusInfo(), delegate.getMaxWaiters(), delegate.getWaitTimeoutMs(), true, true);

        this.delegate = delegate;
        this.deviceModbusInfo = deviceModbusInfo;
        this.deviceIdentity = deviceIdentity;
        this.owner = null;
        this.deviceSerialSource = null;
    }

    /**
     * 带 owner 的设备视图（io-resource-owner §5.3 包装器）：锁获取（acquire/acquirePollingBounded）
     * 自动注入自身 owner——同连接多 slave 逐请求归因，集成事务代码零改动；closeModbus
     * 摘自身账本条目（末源销毁共享连接），RTU 形态另摘经 serial 转发的串口视图。
     *
     * @param delegate 共享的底层ModbusSource
     * @param deviceModbusInfo 当前设备的ModbusInfo
     * @param owner 本设备注册 owner（LEGACY=旧字符串身份包装，仅入账不注入锁）
     * @param deviceSerialSource RTU 带主转发的串口视图（null=TCP / LEGACY 形态）
     */
    public DeviceSpecificModbusSource(ModbusSource delegate, ModbusInfo deviceModbusInfo,
            ResourceOwner owner, SerialSource deviceSerialSource) {
        super(delegate.getModbusInfo(), delegate.getMaxWaiters(), delegate.getWaitTimeoutMs(), true, true);

        this.delegate = delegate;
        this.deviceModbusInfo = deviceModbusInfo;
        this.deviceIdentity = null;
        this.owner = owner;
        this.deviceSerialSource = deviceSerialSource;
    }

    /**
     * @deprecated 使用三参构造函数 {@link #DeviceSpecificModbusSource(ModbusSource, ModbusInfo, String)} 代替
     */
    @Deprecated
    public DeviceSpecificModbusSource(ModbusSource delegate, ModbusInfo deviceModbusInfo) {
        this(delegate, deviceModbusInfo, null);
    }

    public Integer getDeviceSlaveId() {
        return deviceModbusInfo.getSlaveId();
    }

    /**
     * 本视图注册 owner 的锁注入形态：LEGACY 返回 null（LEGACY 无设备身份字段，注入反而
     * 会压制线程 MDC 归因——owner 在=整组投影不与 MDC 混搭，过渡期保持现状）；其余原样。
     */
    private ResourceOwner lockOwner() {
        return owner != null && owner.getLevel() != OwnerLevel.LEGACY ? owner : null;
    }

    // 委托锁管理方法（owner 形态自动注入自身 owner，§5.3 集成事务代码零改动；
    // 显式带 owner 参数的形态原样透传——调用方显式给的 owner 即权威）
    @Override
    public String acquire() {
        return delegate.acquire(lockOwner());
    }

    @Override
    public String acquire(ResourceOwner owner) {
        return delegate.acquire(owner);
    }

    @Override
    public String acquire(long timeout, TimeUnit unit) {
        return delegate.acquire(timeout, unit, lockOwner());
    }

    @Override
    public String acquire(long timeout, TimeUnit unit, ResourceOwner owner) {
        return delegate.acquire(timeout, unit, owner);
    }

    /**
     * 轮询锁获取契约（owner 注入形态）：委托共享 delegate 并注入自身 owner（本类
     * delegateMode 不持有锁状态机，与 {@link #acquire()} 同一委托边界）。语义见
     * {@link ModbusSource#acquirePollingBounded(long)}。
     */
    @Override
    public CompletableFuture<String> acquirePollingBounded(long budgetMs) {
        return delegate.acquirePollingBounded(lockOwner(), budgetMs);
    }

    @Override
    CompletableFuture<String> acquirePollingBounded(ResourceOwner owner, long budgetMs) {
        return delegate.acquirePollingBounded(owner, budgetMs);
    }

    /** 持锁 owner 观测面同样委托（本类 delegateMode 不持有锁状态机）。 */
    @Override
    ResourceOwner getLockAcquireOwner() {
        return delegate.getLockAcquireOwner();
    }

    /** 累计轮询锁忙放弃计数（委托 delegate 的记账，跨设备共享同一把源锁故同一计数）。 */
    @Override
    public long getLockBusySkipCount() {
        return delegate.getLockBusySkipCount();
    }

    @Override
    public boolean release(String releaseKey) {
        return delegate.release(releaseKey);
    }
    
    @Override
    public int getWaitingCount() {
        return delegate.getWaitingCount();
    }

    /**
     * 挂死传输强拆委托给共享 delegate（master/传输资源归 delegate 所有，本类 delegateMode
     * 不持有）。Q-1/A2：DeviceSpecificModbusSource 与 delegate 共用同一把源锁，事务硬超时
     * 路径经本类调用时必须落到真实持有传输的 delegate 上。
     */
    @Override
    public void forceRecoverTransport(String reason) {
        delegate.forceRecoverTransport(reason);
    }
    
    // 直接委托给delegate的带slaveId方法
    @Override
    public CompletableFuture<ReadCoilsResponse> readCoils(int startAddress, int numberOfBits) {
        return delegate.readCoilsWithSlaveId(deviceModbusInfo.getSlaveId(), startAddress, numberOfBits);
    }
    
    @Override
    public CompletableFuture<ReadDiscreteInputsResponse> readDiscreteInputs(int startAddress, int numberOfBits) {
        return delegate.readDiscreteInputsWithSlaveId(deviceModbusInfo.getSlaveId(), startAddress, numberOfBits);
    }
    
    @Override
    public CompletableFuture<ReadExceptionStatusResponse> readExceptionStatus() {
        return delegate.readExceptionStatusWithSlaveId(deviceModbusInfo.getSlaveId());
    }
    
    @Override
    public CompletableFuture<ReadHoldingRegistersResponse> readHoldingRegisters(int startAddress, int numberOfRegisters) {
        return delegate.readHoldingRegistersWithSlaveId(deviceModbusInfo.getSlaveId(), startAddress, numberOfRegisters);
    }
    
    @Override
    public CompletableFuture<ReadInputRegistersResponse> readInputRegisters(int startAddress, int numberOfRegisters) {
        return delegate.readInputRegistersWithSlaveId(deviceModbusInfo.getSlaveId(), startAddress, numberOfRegisters);
    }
    
    @Override
    public CompletableFuture<ReportSlaveIdResponse> reportSlaveId() {
        return delegate.reportSlaveIdWithSlaveId(deviceModbusInfo.getSlaveId());
    }
    
    @Override
    public CompletableFuture<WriteCoilResponse> writeCoil(int address, boolean value) {
        return delegate.writeCoilWithSlaveId(deviceModbusInfo.getSlaveId(), address, value);
    }
    
    @Override
    public CompletableFuture<WriteCoilsResponse> writeCoils(int startAddress, boolean[] values) {
        return delegate.writeCoilsWithSlaveId(deviceModbusInfo.getSlaveId(), startAddress, values);
    }
    
    @Override
    public CompletableFuture<WriteMaskRegisterResponse> writeMaskRegister(int address, int andMask, int orMask) {
        return delegate.writeMaskRegisterWithSlaveId(deviceModbusInfo.getSlaveId(), address, andMask, orMask);
    }
    
    @Override
    public CompletableFuture<WriteRegisterResponse> writeRegister(int address, int value) {
        return delegate.writeRegisterWithSlaveId(deviceModbusInfo.getSlaveId(), address, value);
    }
    
    @Override
    public CompletableFuture<WriteRegistersResponse> writeRegisters(int startAddress, short[] values) {
        return delegate.writeRegistersWithSlaveId(deviceModbusInfo.getSlaveId(), startAddress, values);
    }
    
    // 委托其他必要的方法
    @Override
    public void registerIntegration(String identity) {
        delegate.registerIntegration(identity);
    }

    @Override
    public void registerIntegration(ResourceOwner owner) {
        delegate.registerIntegration(owner);
    }

    @Override
    public void removeIntegration(String identity) {
        delegate.removeIntegration(identity);
    }

    @Override
    public void removeIntegration(ResourceOwner owner) {
        delegate.removeIntegration(owner);
    }

    @Override
    public boolean isModbusOpen() {
        return delegate.isModbusOpen();
    }

    /**
     * 收尾本设备视图（io-resource-owner §5.3 收尾序）：先摘本设备的串口视图，再摘 modbus
     * 账本（末源才销毁 master）。顺序不可倒——账目契约是「一 owner 一条账目」：同 owner
     * 双视图注册属误用形态，第二次 close 时 {@code delegate.closeModbus(owner)} 会因账目
     * 已摘抛 IAE（显式信号，不静默），若串口视图清理排在它之后会被该异常短路，留下
     * serial 侧半收尾视图。closePort 幂等（unregisterSource 的 remove 守卫，重复关/
     * 已摘视图零副作用）；master 传输流直取共享串口对象（ModbusSerialPortWrapper
     * getInputStream/getOutputStream 路径），视图摘除不伤在用 master，先注销者不伤
     * 后继设备。
     */
    @Override
    public void closeModbus() {
        if (owner != null) {
            if (deviceSerialSource != null) {
                deviceSerialSource.closePort();
            }
            delegate.closeModbus(owner);
        } else if (deviceIdentity != null) {
            delegate.closeModbus(deviceIdentity);
        }
        // owner/deviceIdentity 皆 null（@Deprecated 两参构造）保持旧行为
    }
    
    @Override
    public int getMaxWaiters() {
        return delegate.getMaxWaiters();
    }
    
    @Override
    public int getWaitTimeoutMs() {
        return delegate.getWaitTimeoutMs();
    }
    
    @Override
    public ModbusInfo getModbusInfo() {
        return delegate.getModbusInfo();
    }
}
