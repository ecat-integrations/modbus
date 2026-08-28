package com.ecat.integration.ModbusIntegration.Sdk;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

import com.ecat.core.Device.RemovalHost;
import com.ecat.core.Task.LockBusySkippedException;
import com.ecat.core.Task.runner.PeriodicChain;
import com.ecat.core.Task.runner.PeriodicRunner;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.ModbusTransactionStrategy;

/**
 * modbus 域主动轮询 SDK（传输 SDK 层轮询模式的 modbus 形态）：把设备仓的
 * 「{@code getScheduledExecutor().scheduleWithFixedDelay(this::readAndUpdate, ...)} +
 * readAndUpdate 壳（executePolling 包裹 + exceptionally/isLockBusySkip 样板）」折叠成
 * builder 声明——调度注册（域自持定时，见下）/源锁/硬超时/跳拍/异常韧性/统一日志全托管；
 * 设备仓的执行词汇只剩 round 函数（读什么）+ 属性灌入（onRound 里解析）。
 *
 * <p><b>用法</b>（chko 最简形态迁移示例）：
 * <pre>{@code
 * ModbusPolling.on(this, modbusSource)              // this = 设备宿主（RemovalHost）
 *         .round(source -> source.readHoldingRegisters(BLOCK.startAddress, BLOCK.registerCount)
 *                 .thenApply(this::parseAndUpdate))   // 业务：读+解析+attr.updateValue 灌入
 *         .every(5, TimeUnit.SECONDS)
 *         .start();                                    // 返回 PollingHandle；SDK 内绑宿主生命周期
 * }</pre>
 *
 * <p><b>round 契约</b>：{@code Function<ModbusSource, CompletableFuture<Boolean>>}——入参是
 * 已持锁的 source（事务体与既有 executePolling lambda 同签名，迁移=搬函数体）；
 * Boolean=本轮业务成功（false 触发统一 warn），异常=传输错误（统一 error）。多段
 * thenCompose/allOf 链一等公民（saimosen 多段并行形态原样可搬）。
 *
 * <p><b>周期语义与调度自持</b>（29 号 v2 S1：modbus 域脱离 core 调度引擎）：周期链 =
 * core 库 {@link PeriodicRunner}/{@link PeriodicChain}（完成点重排/逐轮 MDC/句柄竞态
 * 收口/永不注销由 core 统一承载）+ 域自持定时池 {@link ModbusSdkTimers}（daemon 命名
 * 线程，集成 onRelease 停机）+ 域侧网格策略 {@link ModbusPollingSchedule}。
 * {@link #every} 默认 fixedDelay（事务 CF 完成点+period=下轮）；FixedRate 语义
 * （aogan/ebyte/epever/juyingele/zhiqwl/generic-device 六仓现状）用 {@link #fixedRate()}
 * 声明（名义网格、到拍上轮未完成则跳拍、滞后超一个整周期过期即弃）。
 *
 * <p><b>跳拍语义</b>（源锁忙跳拍不是失败、正常完成、不中断轮询）：
 * {@code executePolling} 的 {@code LockBusySkippedException} 在本类内部消化
 * （17 号 §2.1：不再外泄给设备仓），报告 {@link RoundReport.Outcome#LOCK_BUSY_SKIPPED}。
 *
 * <p><b>超时</b>：事务链路内建硬超时（{@code boundedReadWaitMs} = requestTimeoutMs×6，
 * 超时强拆传输）由 {@link ModbusTransactionStrategy} 保证、无需声明——SDK 级
 * timeoutMs 收紧词汇零消费已删（超时结局仍报告
 * {@link RoundReport.Outcome#TIMED_OUT}，由内建硬超时产生）。
 *
 * <p><b>生命周期</b>（{@link #start()} 内，18 号 §3.3）：轮询句柄经
 * {@link RemovalHost#onRemove} 注册到宿主设备生命周期——设备移除 sweep
 * （{@code cancelManagedTasks}）时 LIFO 执行 cancel，设备仓 stop()/release() 无需再写
 * cancel 样板（{@link PollingHandle#cancel} 幂等，与 sweep 二次调用天然兼容）。
 *
 * @author coffee
 */
public final class ModbusPolling {

    private static final Log log = LogFactory.getLogger(ModbusPolling.class);

    /** 宿主设备（必填）：start() 时把轮询 cancel 注册进宿主移除动作（18 号 §3.3 SDK 内绑）。 */
    private final RemovalHost host;
    private final ModbusSource source;

    /** 每轮读什么（必选，声明一次）。 */
    private Function<ModbusSource, CompletableFuture<Boolean>> round;
    /** 周期（必选，毫秒，正数）。 */
    private long periodMs = -1L;
    /** 首轮延迟（毫秒，默认 0——立即发起首轮）。 */
    private long initialDelayMs = 0L;
    /** true = 名义网格 FixedRate 语义（到拍上轮未完成跳拍）；默认 fixedDelay 完成点语义。 */
    private boolean fixedRate;
    /** 任务名（可选）：SDK 侧日志/观测定位标签。 */
    private String taskName;
    /** 单轮观测回调（可选）：每轮完成时同步通知一次（异常已隔离，见 report）。 */
    private Consumer<RoundReport> onRound;
    /** 纳米钟（默认系统单调钟；Sdk 包内确定性网格测试注入假钟用，非公共 API）。 */
    private LongSupplier nanoClock = System::nanoTime;

    /** 轮次序号（日志/报告定位用，单调递增）。 */
    private final AtomicLong roundSeq = new AtomicLong();
    /** 成功轮数（业务返回 true 的轮，PollingHandle.getCompletedRounds 口径）。 */
    private final AtomicLong completedRounds = new AtomicLong();

    /**
     * 断连态去重标志（comm 熔断退役后的补偿观测）：true = 当前处于连续失败期。
     * 失败轮 = FAILED/TIMED_OUT/BUSINESS_FALSE；锁忙轮是内部跳过信号，不改本态。
     * 周期链内轮次串行（完成点重排/到拍跳拍），volatile 足够。
     */
    private volatile boolean linkDown;

    private ModbusPolling(RemovalHost host, ModbusSource source) {
        this.host = host;
        this.source = source;
    }

    /**
     * 在宿主设备的 modbus 源上建轮询（同一源可建多个 polling 实例，源锁保证帧串行；
     * {@code DeviceSpecificModbusSource} 是 ModbusSource 子类，直接传入）。host 必填——
     * 轮询生命周期随宿主设备（start() 内绑 onRemove），生产传设备自身 {@code this}，
     * 测试/独立场景传假宿主（{@code action -> {}} 或收集断言型）。
     */
    public static ModbusPolling on(RemovalHost host, ModbusSource source) {
        if (host == null) {
            throw new IllegalArgumentException("ModbusPolling.on(null host) 不允许——轮询必须挂到宿主生命周期");
        }
        if (source == null) {
            throw new IllegalArgumentException("ModbusPolling.on(host, null source) 不允许");
        }
        return new ModbusPolling(host, source);
    }

    /** 声明每轮读什么（必选，只能声明一次；签名与既有 executePolling lambda 一致，迁移=搬函数体）。 */
    public ModbusPolling round(Function<ModbusSource, CompletableFuture<Boolean>> round) {
        if (round == null) {
            throw new IllegalArgumentException("round(null) 不允许");
        }
        if (this.round != null) {
            throw new IllegalStateException("round 已声明过（每轮读什么只能有一个定义）");
        }
        this.round = round;
        return this;
    }

    /** 声明轮询周期（必选）：默认 fixedDelay 语义（本轮事务完成点 + period = 下轮发射点）。 */
    public ModbusPolling every(long period, TimeUnit unit) {
        if (period <= 0 || unit == null) {
            throw new IllegalArgumentException("every(period, unit) 要求 period > 0 且 unit 非空");
        }
        this.periodMs = unit.toMillis(period);
        return this;
    }

    /** 声明首轮延迟（默认 0：立即发起首轮）。 */
    public ModbusPolling initialDelay(long delay, TimeUnit unit) {
        if (delay < 0 || unit == null) {
            throw new IllegalArgumentException("initialDelay(delay, unit) 要求 delay >= 0 且 unit 非空");
        }
        this.initialDelayMs = unit.toMillis(delay);
        return this;
    }

    /**
     * 切换为固定速率语义（名义网格发射、到拍时上轮未完成的拍跳过）——aogan/ebyte/epever/
     * juyingele/zhiqwl/modbus-generic-device 六仓既有 FixedRate 节律的等价形态。
     */
    public ModbusPolling fixedRate() {
        this.fixedRate = true;
        return this;
    }

    /** 周期任务名（SDK 日志/观测定位；默认 source 连接标识）。 */
    public ModbusPolling named(String taskName) {
        if (taskName == null || taskName.isEmpty()) {
            throw new IllegalArgumentException("named(taskName) 要求非空");
        }
        this.taskName = taskName;
        return this;
    }

    /**
     * 单轮观测回调（可选）：每轮完成时同步通知一次，载荷 {@link RoundReport}（结局分类）。
     * 生产代码零消费，但 24 个 modbus 族设备仓的 PollingLockBusySkipTest 回归锁与
     * sensecap 探针在用——R8 复核判「测试消费=真实消费」保留。观测与主链正交：
     * 回调抛异常只记 warn，不影响轮询记账与重排。
     */
    public ModbusPolling onRound(Consumer<RoundReport> onRound) {
        if (onRound == null) {
            throw new IllegalArgumentException("onRound(null) 不允许");
        }
        this.onRound = onRound;
        return this;
    }

    /**
     * 到点单发糖（B 族收编备用）：{@code ms} 毫秒后正常完成的 {@link CompletableFuture}，
     * 经域自持定时器提交（MDC 传播内置）。round 体内多段块读之间留隙以适应设备性能：
     * <pre>{@code
     * ModbusPolling polling = ModbusPolling.on(this, source);
     * polling.round(src -> src.readHoldingRegisters(B1.start, B1.count)
     *                 .thenCompose(v -> polling.delay(50))          // 块间留隙 50ms
     *                 .thenCompose(v -> src.readHoldingRegisters(B2.start, B2.count))
     *                 .thenApply(v -> Boolean.TRUE))
     *         .every(5, TimeUnit.SECONDS)
     *         .start();
     * }</pre>
     * 设备侧两步构建（先建 polling 再挂 round/start）保证 round 体可无竞态引用本方法。
     * 延迟属在飞轮次的一部分，不注册移除动作（RemovalHost 非阻塞契约；链 cancel 后迟到
     * 完成无人消费，无害）。
     *
     * @param ms 延迟毫秒（须 &gt; 0——零留隙无意义，别调本方法）
     * @return 到点以 null 完成的 CF（不因定时器本身失败）
     * @throws java.util.concurrent.RejectedExecutionException 定时池已停机（终端态，显式信号）
     */
    public CompletableFuture<Void> delay(long ms) {
        if (ms <= 0) {
            throw new IllegalArgumentException("delay(ms) 要求 ms > 0（零留隙无意义）: " + ms);
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        ModbusSdkTimers.fireAfter(() -> future.complete(null), ms);
        return future;
    }

    /** 纳米钟注入（默认系统单调钟；Sdk 包内确定性网格测试驱动用，非消费方 API）。 */
    ModbusPolling withNanoClock(LongSupplier clock) {
        if (clock == null) {
            throw new IllegalArgumentException("withNanoClock(null) 不允许");
        }
        this.nanoClock = clock;
        return this;
    }

    /**
     * 启动轮询（29 号 v2 S1：域自持调度）：周期链 = core 库 PeriodicChain（在
     * {@link ModbusSdkTimers} 域池上自排，每拍一单发；事务 CF 完成点驱动重排，任何终态
     * ——含 begin 同步抛——都重排，永不注销）+ 域侧网格策略 {@link ModbusPollingSchedule}
     * （fixedDelay 完成点+period / fixedRate 名义网格+在飞跨拍跳过+过期即弃）。句柄经
     * {@link RemovalHost#onRemove} 注册到宿主设备生命周期（设备移除 sweep 时 LIFO 执行
     * cancel）。
     *
     * @return 轮询生命周期句柄（cancel 幂等；生命周期已内绑宿主，无需调用方保存 cancel）
     */
    public PollingHandle start() {
        if (round == null) {
            throw new IllegalStateException("ModbusPolling.start() 前必须声明 round(...)（每轮读什么）");
        }
        if (periodMs <= 0) {
            throw new IllegalStateException("ModbusPolling.start() 前必须声明 every(period, unit)（多久一轮）");
        }
        PeriodicRunner runner = ModbusSdkTimers.runner();
        ModbusPollingSchedule schedule = fixedRate
                ? ModbusPollingSchedule.fixedRate(periodMs, initialDelayMs, nanoClock, label())
                : ModbusPollingSchedule.fixedDelay(periodMs, initialDelayMs, nanoClock, label());
        // 首发即发（默认 initialDelay=0；声明 D 则首拍与名义锚点都从 D 起算）；
        // 在已停机（终端态）的池上起链由 REE 显式上抛（调用方错误，严格模式）
        PeriodicChain chain = runner.periodic(label(), this::runRound, schedule).start();
        PollingHandle handle = new Handle(chain);
        // SDK 内绑宿主生命周期（18 号 §3.3）：设备移除 sweep 执行本动作即停轮询；
        // cancel 纯标记不中断在飞事务（非阻塞契约），幂等与 sweep 二次调用天然兼容
        host.onRemove(handle::cancel);
        log.info("ModbusPolling 启动: {}, period: {}ms, mode: {}", label(), periodMs,
                fixedRate ? "fixedRate" : "fixedDelay");
        return handle;
    }

    /**
     * 单轮执行体（周期链每拍调用的 Supplier，SDK 定时线程上执行）：
     * executePolling 事务（源锁 + 内建硬超时 + release/强拆全复用
     * {@link ModbusTransactionStrategy}）→ 结局分类/统一日志/onRound 通知/断连状态转移。
     *
     * <p>对周期链的返回值语义：true=本轮无失败（成功/跳拍/锁忙——都不是失败），
     * false=业务显式失败；异常=传输错误——任何终态链都重排（永不注销），生产失败观测
     * 走统一日志/断连状态转移行。真实五分类结局以 {@link RoundReport} 为准
     * （经 onRound，测试回归锁消费）。
     */
    CompletableFuture<Boolean> runRound() {
        final long roundIndex = roundSeq.incrementAndGet();

        final long startNanos = System.nanoTime();
        CompletableFuture<Boolean> transaction = ModbusTransactionStrategy.executePolling(source, round);
        return transaction.handle((result, error) -> {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            if (error != null) {
                if (LockBusySkippedException.isLockBusySkip(error)) {
                    // LockBusy 内部消化：本轮跳过非设备错误——正常完成（不算失败），计数走源级记账
                    log.debug("ModbusPolling 轮 {} 源锁忙跳过（下周期再试）: {}", roundIndex, label());
                    report(new RoundReport(roundIndex, RoundReport.Outcome.LOCK_BUSY_SKIPPED,
                            rootOf(error), durationMs));
                    return Boolean.TRUE;
                }
                Throwable root = rootOf(error);
                if (root instanceof TimeoutException) {
                    log.error("ModbusPolling 轮 {} 超时（{}ms）: {}", roundIndex, durationMs, label());
                    report(new RoundReport(roundIndex, RoundReport.Outcome.TIMED_OUT, root, durationMs));
                } else {
                    log.error("ModbusPolling 轮 {} 传输异常: {}", roundIndex, label(), root);
                    report(new RoundReport(roundIndex, RoundReport.Outcome.FAILED, root, durationMs));
                }
                // 异常语义：向周期链异常完成（传输错误终态，链照常重排——永不注销）
                throw error instanceof CompletionException ? (CompletionException) error
                        : new CompletionException(error);
            }
            if (Boolean.FALSE.equals(result)) {
                log.warn("ModbusPolling 轮 {} 业务返回 false（读了但未成业务态）: {}", roundIndex, label());
                report(new RoundReport(roundIndex, RoundReport.Outcome.BUSINESS_FALSE, null, durationMs));
            } else {
                completedRounds.incrementAndGet();
                report(new RoundReport(roundIndex, RoundReport.Outcome.SUCCESS, null, durationMs));
            }
            return result;
        });
    }

    /** onRound 通知（观测面隔离：调用方代码异常只记 warn，不破坏轮询主链）+ 断连态转移。 */
    private void report(RoundReport report) {
        trackLinkState(report);
        Consumer<RoundReport> observer = this.onRound;
        if (observer == null) {
            return;
        }
        try {
            observer.accept(report);
        } catch (RuntimeException observerFailure) {
            log.warn("ModbusPolling onRound 观察者异常（已隔离，不影响轮询）: " + label(),
                    observerFailure);
        }
    }

    /**
     * 断连状态转移（去重）：失败结局进入断连态（首败一行 WARN），SUCCESS 退出（一行 INFO）
     * ——per-round ERROR/WARN 由 runRound 照打（全栈可 grep 定位根因），转移行只给运维
     * 一眼可见的 连续断连/恢复 时间线。
     */
    private void trackLinkState(RoundReport report) {
        switch (report.getOutcome()) {
            case FAILED:
            case TIMED_OUT:
            case BUSINESS_FALSE:
                if (!linkDown) {
                    linkDown = true;
                    Throwable error = report.getError();
                    log.warn("ModbusPolling {} link DOWN ({}), recovery will be logged", label(),
                            report.getOutcome() + (error == null ? "" : ": " + error.getMessage()));
                }
                break;
            case SUCCESS:
                if (linkDown) {
                    linkDown = false;
                    log.info("ModbusPolling {} link RECOVERED (rounds succeeding again)", label());
                }
                break;
            case LOCK_BUSY_SKIPPED:
                // 锁忙是内部跳过信号非通讯失败：不改断连态（源侧另有记账与限频日志）
                break;
        }
    }

    /** 日志/观测标签：named 优先，默认源连接标识。 */
    private String label() {
        return taskName != null ? taskName : "modbus-polling[" + source.getModbusInfo() + "]";
    }

    /** 剥 CompletionException/ExecutionException 包装取根因（分类与报告用根因，透传用原样）。 */
    private static Throwable rootOf(Throwable t) {
        Throwable cur = t;
        while ((cur instanceof CompletionException || cur instanceof ExecutionException)
                && cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur;
    }

    /** {@link PollingHandle} 实现：包装 core 周期链句柄（链语义透传，不二次包装）。 */
    private final class Handle implements PollingHandle {

        private final PeriodicChain chain;

        Handle(PeriodicChain chain) {
            this.chain = chain;
        }

        @Override
        public void cancel() {
            // 不中断线程：在飞事务 CF 自行完成并释放源锁（IO 旁池线程归旁池管）
            chain.cancel();
        }

        @Override
        public boolean isRunning() {
            return chain.isRunning();
        }

        @Override
        public long getCompletedRounds() {
            return completedRounds.get();
        }
    }
}
