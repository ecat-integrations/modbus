package com.ecat.integration.ModbusIntegration.Sdk;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.ecat.core.Device.RemovalHost;
import com.ecat.core.Task.NamedThreadFactory;
import com.ecat.core.Task.runner.PeriodicRunner;
import com.ecat.core.Task.runner.ShotScheduler;

/**
 * modbus SDK 的定时执行器所有权与测试缝（29 号 v2 S1：modbus 域脱离 core 调度引擎，
 * 定时语义归传输 SDK；周期链/MDC 原语消费 core 库 {@link PeriodicRunner}，本类只保留
 * 「域自持池 + 缝」——与 http 域 S0 的 HttpSdkTimers 同构）。
 *
 * <p><b>为什么自持</b>：SDK 的周期语义（fixedDelay 完成点重排 / fixedRate 名义网格 /
 * 过期即弃）内化在 {@link ModbusPollingSchedule} 与轮体里，对定时器的全部需求收敛为
 * 一种原语——「MDC 包装的到点单发」（core PeriodicRunner 实现）：周期链每拍、轮超时
 * 执法、{@code delay(ms)} 留隙糖。本类持有唯一默认池（daemon、命名
 * {@code ecat-modbus-sched-N}）。</p>
 *
 * <p><b>池尺寸=2 论证</b>（线程预算可观测面，改尺寸须同步改论证与守护测试）：定时线程
 * 只执行「发起段」——{@code fireRound} 体是 µs 级提交：
 * {@code executePolling} 的 tryAcquire（非阻塞）+ 轮体（读请求 {@code supplyAsync}
 * 提交，O(1)）+ 内建硬超时/超时执法的单发登记与到点体（complete future，µs 级）；
 * 真实阻塞 IO（master.send 等回音）与恢复动作（destroy/init）全部
 * 在 {@link com.ecat.integration.ModbusIntegration.ModbusIoPool}（16 条）上，本池零
 * 阻塞等待。最坏尖峰=冷启动 ~57 源首发齐发（µs 级任务 ×57），2 条线程亚毫秒吸干；
 * 单条被消费方异常轮体钉死时，其余链仍能在另一条上准点到拍。结算续段
 * （重排下一拍）在事务完成线程（IO 旁池线程）上执行，不占本池。</p>
 *
 * <p><b>MDC</b>：单发的提交时捕获（coordinate）、到拍恢复、无 traceId 补生成，由
 * {@link PeriodicRunner#fireAfter(Runnable, long)} 内置（core TraceContext 同一实现）；
 * 周期链的逐轮包装（提交 coordinate+每轮新 traceId）由 core PeriodicChain 内置。</p>
 *
 * <p><b>生命周期</b>：唯一停机入口 {@link #shutdown()}（幂等、终端态，不自动复活），
 * 由 modbus 集成 {@code onRelease} 在 IO 旁池关停后调用（消费集成先于依赖集成释放，
 * 链路已先经 RemovalHost 收口，这里是兜底强制停）。停机后新提交抛
 * {@link RejectedExecutionException}——R-F 域间统一（对齐 serial/tcp/http 三域的
 * 终端态契约；核心生命周期 remove→onRelease 后必是新 JVM，同 JVM 重初始化属生产
 * 不存在的边界，测试隔离经 {@link #resetForTest()} 重开窗口）。</p>
 *
 * <p><b>测试缝</b>：{@code bindForTest}/{@code unbindForTest} 注入替身（包内可见，
 * 生产禁用）；{@code resetForTest} 仅供测试独占默认池。缝类型窄化为
 * {@link ShotScheduler}——SDK 只消费单发形态，不暴露整个执行器面；{@link #runner()}
 * 在当前缝上取 core PeriodicRunner，bind 替身后链路整体走替身。</p>
 *
 * <p><b>S4 已落地</b>：core {@code TimeoutScheduler}（{@code Task.execution} 包）已整删，
 * {@code ModbusTransactionStrategy} 的两处计时委托（事务硬超时 + apply 看门狗）已改挂本池
 * {@code fireAfter}。「零阻塞」定容论证经此成立的前提：apply 看门狗与硬超时到点体里的
 * 恢复动作（{@code forceRecoverTransport}，destroy/init 可阻塞）一律卸载到
 * {@link com.ecat.integration.ModbusIntegration.ModbusIoPool} 执行，到点体本身只做
 * µs 级动作（记日志 + release + complete + 提交恢复）——契约由守护测试
 * {@code ModbusWedgeRecoveryIoPoolOffloadTest} 锁定。</p>
 *
 * @author coffee
 */
public final class ModbusSdkTimers {

    /** 默认池尺寸（线程预算论证见类注释；守护测试 ModbusSdkTimersTest 锁定）。 */
    private static final int POOL_SIZE = 2;

    /** 懒持有的默认池：类加载不建线程，首个真实单发才起步 worker。 */
    private static volatile ScheduledThreadPoolExecutor pool;

    /** 停机终端态标志：置位后不再惰性建池（严格模式，停机不自动复活）。 */
    private static volatile boolean terminated;

    /** 测试注入的缝；null = 走默认池。仅测试代码可写。 */
    private static volatile ShotScheduler bound;

    /** 当前缝上的 core 周期链工具（lambda 逐调用解析 seam，bind 替身即时生效）。 */
    private static final PeriodicRunner RUNNER = PeriodicRunner.on(
            (command, delayMillis) -> seam().fireAfter(command, delayMillis));

    private ModbusSdkTimers() {
    }

    /** 当前缝上的 PeriodicRunner：周期链与 MDC 单发原语（ModbusPolling 消费面）。 */
    static PeriodicRunner runner() {
        return RUNNER;
    }

    /**
     * 提交一个 MDC 包装单发（提交时捕获上下文、到拍恢复、无 traceId 补生成）。
     *
     * <p>modbus 域内跨包消费面（{@code ModbusPolling} 轮超时执法 /
     * {@code ModbusTransactionStrategy} 事务硬超时 + apply 看门狗计时），故 public——
     * 与 serial 域 {@code SerialSdkTimers.fireAfter} 同款可见性。
     *
     * @throws java.util.concurrent.RejectedExecutionException 缝/池已停机（终端态，显式信号）
     */
    public static ScheduledFuture<?> fireAfter(Runnable command, long delayMillis) {
        return RUNNER.fireAfter(command, delayMillis);
    }

    /**
     * 可撤销静态延迟（SDK delay 统一词汇的可撤销形态）：{@code ms} 毫秒后以 null 完成的
     * {@link CompletableFuture}，经本域定时器 MDC 包装单发到点完成（提交时捕获上下文、
     * 到拍恢复）。round 体内块间留隙可达轮询实例，优先实例糖
     * {@link ModbusPolling#delay(long)}；本静态入口供无实例上下文的命令型设备延迟写
     * （生产消费：qdrgdz 校时命令 / saimosen 颗粒物零点检查 / tianhong 远程校准）。
     * 把「撤销待发拍」注册进 {@code host.onRemove}（RemovalHost 移除生命周期，纯标记
     * cancel 不打断在飞执行——非阻塞契约天然合规），满足写路径 IO 副作用的 stop 可撤
     * 契约：宿主 sweep 后 pending 延迟不再发射，返回的 CF 不完成（消费链随宿主死亡
     * 不再推进——与设备托管账本 cancel 原句柄同语义）。
     *
     * @param host 移除动作宿主（生产 = 设备自身，RemovalHost 单方法面）
     * @param ms   延迟毫秒，须 &gt;= 0（0=立即完成，显式声明无延迟）
     * @return 到点以 null 完成的 CF（宿主移除后不完成）
     * @throws RejectedExecutionException 默认池已停机（终端态）；或宿主已 sweep
     *         （后者时刚提交的待发拍已先行撤销再上抛，不留悬挂拍）
     */
    public static CompletableFuture<Void> delay(RemovalHost host, long ms) {
        if (host == null) {
            throw new IllegalArgumentException("delay(host, ms) 要求 host 非 null（可撤销语义依赖宿主移除生命周期）");
        }
        if (ms < 0) {
            throw new IllegalArgumentException("delay(host, ms) 要求 ms >= 0（0=立即完成）: " + ms);
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        ScheduledFuture<?> shot = fireAfter(() -> future.complete(null), ms);
        try {
            host.onRemove(() -> shot.cancel(false));
        } catch (RejectedExecutionException hostAlreadySwept) {
            // 宿主已 sweep：先撤销刚提交的待发拍再上抛（严格模式——不留悬挂拍，也不吞显式失败）
            shot.cancel(false);
            throw hostAlreadySwept;
        }
        return future;
    }

    /**
     * 停机钩子（幂等、终端态）：撤销全部待发单发并中断在飞执行，此后新提交抛
     * {@link RejectedExecutionException}——不自动复活（R-F：与 serial/tcp/http 三域统一，
     * 同 JVM 重初始化集成属生产不存在的边界——core 生命周期 remove→onRelease 后必是
     * 新 JVM；测试隔离走 {@link #resetForTest()}）。
     */
    public static void shutdown() {
        synchronized (ModbusSdkTimers.class) {
            terminated = true;
            ScheduledThreadPoolExecutor current = pool;
            if (current != null) {
                current.shutdownNow();
            }
            pool = null;
        }
    }

    /** 真 {@link ScheduledExecutorService} 到缝的适配器（联调测试自备真池用）。 */
    static ShotScheduler forScheduledExecutor(ScheduledExecutorService executor) {
        return (command, delayMillis) -> executor.schedule(command, delayMillis, TimeUnit.MILLISECONDS);
    }

    // ==================== 以下均为测试缝（生产禁用） ====================

    /** 注入替身缝（仅测试）。 */
    static void bindForTest(ShotScheduler seam) {
        if (seam == null) {
            throw new IllegalArgumentException("bindForTest(null) 不允许——解除用 unbindForTest()");
        }
        bound = seam;
    }

    /** 解除注入，恢复默认池。 */
    static void unbindForTest() {
        bound = null;
    }

    /**
     * 复位到未建池状态（仅测试基建：隔离其他测试类经 onRelease 关池的顺序影响——
     * 生产停机是终端态，只有测试能重开窗口）。modbus 域内跨包测试消费（父包
     * {@code ModbusIntegration} 的轮询/策略测试类），故 public——与 serial 域
     * {@code SerialSdkTimers.resetForTest} 同款可见性依据。
     */
    public static void resetForTest() {
        synchronized (ModbusSdkTimers.class) {
            ScheduledThreadPoolExecutor current = pool;
            if (current != null) {
                current.shutdownNow();
            }
            pool = null;
            terminated = false;
        }
    }

    private static ShotScheduler seam() {
        ShotScheduler testSeam = bound;
        if (testSeam != null) {
            return testSeam;
        }
        return defaultSeam();
    }

    private static ShotScheduler defaultSeam() {
        if (terminated) {
            throw new RejectedExecutionException("modbus SDK timers terminated (onRelease terminal state)");
        }
        ScheduledThreadPoolExecutor current = pool;
        if (current == null) {
            synchronized (ModbusSdkTimers.class) {
                if (terminated) {
                    throw new RejectedExecutionException("modbus SDK timers terminated (onRelease terminal state)");
                }
                if (pool == null) {
                    ScheduledThreadPoolExecutor created = new ScheduledThreadPoolExecutor(
                            POOL_SIZE, new NamedThreadFactory("ecat-modbus-sched", true));
                    // 链路 cancel/超时撤销高频（每轮一撤）：取消即出队，防队列滞留
                    created.setRemoveOnCancelPolicy(true);
                    pool = created;
                }
                current = pool;
            }
        }
        return forScheduledExecutor(current);
    }
}
