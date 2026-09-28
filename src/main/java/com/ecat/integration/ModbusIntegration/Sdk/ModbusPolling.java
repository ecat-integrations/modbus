package com.ecat.integration.ModbusIntegration.Sdk;

import java.util.ArrayList;
import java.util.List;
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
import com.ecat.core.Utils.Mdc.DeviceMdcContext;
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
 * <p><b>多段轮 roundChain</b>：块间需留隙的多块读设备用 {@link #roundChain()} 声明
 * （{@code held(gap held)* end}）——每段独立源锁事务、段间留隙在锁外（写者可在留隙窗
 * 取锁）；体内 delay 留隙的单段 round() 形态整轮持锁，仅适合锁内短节拍。段体 false/异常
 * 中止不追读后续段，轮结局分类与单段形态一致。
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

    /** 每轮读什么（必选，声明一次；单段 round() 形态或多段 roundChain() 形态二选一）。 */
    private Function<ModbusSource, CompletableFuture<Boolean>> round;
    /**
     * 多段轮声明（{@link #roundChain()} 链上逐段登记；null = 未声明，单段 round() 形态）。
     * 声明期单线程构建、运行期只读，无需并发容器。
     */
    private List<Function<ModbusSource, CompletableFuture<Boolean>>> chainSegments;
    /** 与 chainSegments 对齐的段间留隙毫秒（size = 段数-1；end() 校验）。 */
    private List<Long> chainGaps;
    /** roundChain() 已起建标志（段尚未声明也锁定与 round() 的互斥——声明不得半途换轨）。 */
    private boolean chainDeclared;
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
        if (chainDeclared) {
            throw new IllegalStateException("roundChain 已声明过，round() 与 roundChain() 互斥");
        }
        this.round = round;
        return this;
    }

    /**
     * 声明多段轮（与 round() 互斥，二选一）：每段 {@link RoundChain#held} 是一个独立源锁
     * 事务（独立预算取锁、独立硬超时、完成即释放），相邻段之间 {@link RoundChain#gap}
     * 留隙在源锁临界区之外——设备性能要求的块间节拍不再变成持锁时长（整轮单事务形态下
     * 体内 delay 留隙会把锁持有到轮末，挤爆写命令的有界等待预算）。
     */
    public RoundChain roundChain() {
        if (round != null) {
            throw new IllegalStateException("round 已声明过，roundChain() 与 round() 互斥");
        }
        if (chainDeclared) {
            throw new IllegalStateException("roundChain 已声明过（每轮读什么只能有一个定义）");
        }
        chainDeclared = true;
        return new RoundChain();
    }

    /**
     * 多段轮声明链（{@link #roundChain()} 起建）：{@code held(gap held)* end} 的声明序
     * 由状态机 fail-fast 保证（gap 必须夹在两段之间、不得重复/尾随；end 校验 gap 数 =
     * 段数-1）。段体契约与 round() 段一致（Boolean=业务成功，异常=传输错误）；段体 false
     * 或异常 ⇒ 中止不追读后续段，轮结局=该段结局。声明期单线程、运行期只读。
     */
    public final class RoundChain {

        /** 声明状态：0=待首段，1=待 gap 或 end（上一声明是 held），2=待 held（上一声明是 gap）。 */
        private int state;
        private boolean ended;

        /** 声明一段轮体（独立源锁事务；签名与 round() 一致，迁移=搬函数体）。 */
        public RoundChain held(Function<ModbusSource, CompletableFuture<Boolean>> segment) {
            if (segment == null) {
                throw new IllegalArgumentException("held(null) 不允许");
            }
            if (ended) {
                throw new IllegalStateException("roundChain 已 end()，不得再 held");
            }
            if (chainSegments == null) {
                chainSegments = new ArrayList<>();
                chainGaps = new ArrayList<>();
            }
            chainSegments.add(segment);
            state = 1;
            return this;
        }

        /** 声明与上一段之间的留隙毫秒（锁外节拍；须 &gt; 0——零留隙无意义，别调 gap）。 */
        public RoundChain gap(long ms) {
            if (ms <= 0) {
                throw new IllegalArgumentException("gap(ms) 要求 ms > 0（零留隙无意义）: " + ms);
            }
            if (state != 1) {
                throw new IllegalStateException("gap 必须紧跟在 held 之后且不得连续声明");
            }
            chainGaps.add(ms);
            state = 2;
            return this;
        }

        /** 完成多段轮声明，回到 polling 继续周期/启动声明。 */
        public ModbusPolling end() {
            if (ended) {
                throw new IllegalStateException("roundChain 只能 end() 一次");
            }
            if (chainSegments == null || chainSegments.isEmpty()) {
                throw new IllegalStateException("end() 前至少声明一段 held(...)");
            }
            if (state != 1) {
                throw new IllegalStateException("end() 前必须以 held(...) 收尾（尾随 gap 无所属段）");
            }
            if (chainGaps.size() != chainSegments.size() - 1) {
                throw new IllegalStateException("gap 数必须 = 段数-1，实际: gaps="
                        + chainGaps.size() + ", segments=" + chainSegments.size());
            }
            ended = true;
            return ModbusPolling.this;
        }
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
     * 经域自持定时器提交（MDC 传播内置）。round 体内多段块读之间留隙以适应设备性能
     * （留隙属在飞轮次、源锁全程持有；块间需锁外留隙的多块读用 {@link #roundChain()}）：
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
        if (round == null && chainSegments == null) {
            throw new IllegalStateException(
                    "ModbusPolling.start() 前必须声明 round(...) 或完整 roundChain()（每轮读什么）");
        }
        if (periodMs <= 0) {
            throw new IllegalStateException("ModbusPolling.start() 前必须声明 every(period, unit)（多久一轮）");
        }
        PeriodicRunner runner = ModbusSdkTimers.runner();
        ModbusPollingSchedule schedule = fixedRate
                ? ModbusPollingSchedule.fixedRate(periodMs, initialDelayMs, nanoClock, label())
                : ModbusPollingSchedule.fixedDelay(periodMs, initialDelayMs, nanoClock, label());
        // 首发即发（默认 initialDelay=0；声明 D 则首拍与名义锚点都从 D 起算）；
        // 在已停机（终端态）的池上起链由 REE 显式上抛（调用方错误，严格模式）。
        // 设备归属注入（工单 G）：chain.start 捕获起链线程 MDC 全量快照逐轮恢复——scope 把宿主
        // 设备三键写入快照，轮体→dispatchIo 逐帧捕获（ModbusSource 事务层 TX/RX 埋点）即携带
        // 设备归属（共享连接多 slaveId 场景按发起设备逐帧归属），非设备宿主（测试假宿主）no-op
        PeriodicChain chain;
        try (DeviceMdcContext.Scope deviceScope = DeviceMdcContext.scopeOf(host)) {
            chain = runner.periodic(label(), this::runRound, schedule).start();
        }
        PollingHandle handle = new Handle(chain);
        // SDK 内绑宿主生命周期（18 号 §3.3）：设备移除 sweep 执行本动作即停轮询；
        // cancel 纯标记不中断在飞事务（非阻塞契约），幂等与 sweep 二次调用天然兼容
        host.onRemove(handle::cancel);
        log.info("ModbusPolling 启动: {}, period: {}ms, mode: {}, 轮体: {}", label(), periodMs,
                fixedRate ? "fixedRate" : "fixedDelay",
                chainSegments != null ? "roundChain×" + chainSegments.size() : "round");
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
        CompletableFuture<Boolean> transaction = chainSegments != null
                ? executeChain()
                : ModbusTransactionStrategy.executePolling(source, lockWaitBudgetMs(), round);
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

    /**
     * 轮询锁等待预算（20260913-073600 方案 c）：period÷4，clamp [200, 500]ms——锁忙时在
     * 预算内与写命令同队公平等待，耗尽才弃轮。上界 500ms：秒级周期下等待 duty 不侵蚀轮询
     * 节奏；下界 200ms：毫秒级周期也给对端事务留出收尾窗口。
     */
    long lockWaitBudgetMs() {
        return Math.max(200L, Math.min(500L, periodMs / 4));
    }

    /**
     * 多段轮折叠（roundChain 形态的轮体）：段一立即执行；相邻段之间经 {@link #delay(long)}
     * 留隙——留隙窗在源锁临界区之外（上一段事务完成时已在策略层 whenComplete 释放源锁，
     * 写命令可在窗内取锁；本段到点重新走既有有界取锁排队，与写者 FIFO 同队）。段体显式
     * false（轮契约唯一业务失败标记）或异常 ⇒ 不追读后续段（聚合 CF 以该值/异常收尾，
     * 分类交 runRound 统一处理）。gap 单发属在飞轮次的一部分（同 delay 糖），不注册移除动作。
     */
    private CompletableFuture<Boolean> executeChain() {
        CompletableFuture<Boolean> round = ModbusTransactionStrategy.executePolling(
                source, lockWaitBudgetMs(), chainSegments.get(0));
        for (int i = 1; i < chainSegments.size(); i++) {
            Function<ModbusSource, CompletableFuture<Boolean>> next = chainSegments.get(i);
            long gapMs = chainGaps.get(i - 1);
            round = round.thenCompose(previous -> {
                if (Boolean.FALSE.equals(previous)) {
                    return CompletableFuture.completedFuture(Boolean.FALSE);
                }
                return delay(gapMs).thenCompose(gapDone ->
                        ModbusTransactionStrategy.executePolling(source, lockWaitBudgetMs(), next));
            });
        }
        return round;
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
