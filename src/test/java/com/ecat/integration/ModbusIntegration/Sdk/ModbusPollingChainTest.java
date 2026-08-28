package com.ecat.integration.ModbusIntegration.Sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ecat.core.Device.RemovalHost;
import com.ecat.integration.ModbusIntegration.ModbusProtocol;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.ModbusTcpInfo;

/**
 * {@link ModbusPolling} 周期语义（fake 定时缝 + 注入纳米钟驱动，零后台线程零真实时钟；
 * 与 http 域 HttpPollingTest 同构）。周期链已从 core 调度引擎 Supplier 版内化为
 * SDK 自持链（29 号 v2 S1 modbus 域：域自有 STPE + core 库 PeriodicChain）：
 * <ul>
 *   <li>调度契约：start 首发即发（默认 initialDelay=0，声明 initialDelay(D) 则首拍=D）；
 *       {@code every} → 完成点+period 重排（fixedDelay）；{@code fixedRate()} → 名义网格
 *       推进、在飞跨拍跳过（无滞后补跑）、滞后超一个整周期过期即弃（skips=lag/period+1）；</li>
 *   <li>{@code delay(ms)} 留隙糖：返回到点完成的 CF（B 族一次性 schedule 收编备用）；</li>
 *   <li>轮体契约：Boolean=业务成功、异常=传输错误、begin 同步抛按 failedFuture 等价
 *       ——任何终态都重排（永不注销），结局分类经 onRound（RoundReport）断言；</li>
 *   <li>宿主绑定：start 注册恰好一条移除动作，执行即撤待发拍。</li>
 * </ul>
 * 真实锁状态机 source（skipOpen 不建 master 零网络；匿名子类越 protected 构造边界）；
 * 全部断言以「捕获的单发记录 + 手动到拍」表达，无 Thread.sleep。
 */
public class ModbusPollingChainTest {

    /** 1 秒的纳秒表示（断言用，避免魔法数）。 */
    private static final long SECOND_NANOS = TimeUnit.SECONDS.toNanos(1L);

    private FakeModbusTimers timers;
    private RemovalHost host;
    private List<Runnable> removalActions;
    private AtomicLong nanoClock;

    @Before
    public void setUp() {
        timers = new FakeModbusTimers();
        removalActions = new CopyOnWriteArrayList<>();
        host = removalActions::add;
        nanoClock = new AtomicLong(0L);
    }

    @After
    public void tearDown() {
        timers.close();
    }

    /** 真实锁状态机 source（skipOpen=true 不建 master 不碰网络；匿名子类走 protected 构造）。 */
    private ModbusSource newSource(int requestTimeoutMs) {
        return new ModbusSource(
                new ModbusTcpInfo("127.0.0.1", 19999, 1, ModbusProtocol.TCP, requestTimeoutMs),
                1, 500, true, false) {
            @Override
            public void closeModbus() {
            }
        };
    }

    private ModbusPolling readyPolling(ModbusSource source,
            Supplier<CompletableFuture<Boolean>> round) {
        return ModbusPolling.on(host, source)
                .round(src -> round.get())
                .every(5, TimeUnit.SECONDS)
                .withNanoClock(nanoClock::get);
    }

    // ==================== 调度契约（自持周期链） ====================

    @Test
    public void startFiresFirstShotImmediately_byDefault() {
        readyPolling(newSource(2000), () -> CompletableFuture.completedFuture(Boolean.TRUE)).start();
        assertEquals("首发即发（默认 initialDelay=0）", 1, timers.shots.size());
        assertEquals("首拍延迟 0", 0L, timers.shots.get(0).delayMillis);
    }

    @Test
    public void initialDelayAnchorsFirstShot() {
        ModbusPolling.on(host, newSource(2000))
                .round(src -> CompletableFuture.completedFuture(Boolean.TRUE))
                .every(5, TimeUnit.SECONDS)
                .initialDelay(2, TimeUnit.SECONDS)
                .withNanoClock(nanoClock::get)
                .start();
        assertEquals("声明 initialDelay(D) 则首拍延迟=D", 2_000L, timers.shots.get(0).delayMillis);
    }

    @Test
    public void fixedDelayRearmsFromSettlePoint_notFromNominalGrid() throws Exception {
        CompletableFuture<Boolean> pending = new CompletableFuture<>();
        PollingHandle handle = readyPolling(newSource(2000), () -> pending).start();
        timers.fire(0); // 首轮发起：事务 CF 在飞（测试持有）

        assertEquals("事务在飞期间不得重排下一拍（单飞由结构保证）", 1, timers.shots.size());

        // 在飞 2s 后结算：下一拍=结算点+period（名义网格漂移不回收——fixedDelay 语义）
        nanoClock.addAndGet(2L * SECOND_NANOS);
        pending.complete(Boolean.TRUE);
        assertEquals("结算后重排下一拍", 2, timers.shots.size());
        assertEquals("fixedDelay 下一拍=结算点+5s（2s 在飞不缩短等待）",
                5_000L, timers.shots.get(1).delayMillis);
        handle.cancel();
    }

    @Test
    public void fixedRateAdvancesNominalGrid_andSkipsCrossedTicks() throws Exception {
        CompletableFuture<Boolean> pending = new CompletableFuture<>();
        PollingHandle handle = ModbusPolling.on(host, newSource(2000))
                .round(src -> pending)
                .every(5, TimeUnit.SECONDS)
                .fixedRate()
                .withNanoClock(nanoClock::get)
                .start();
        timers.fire(0); // 首轮在飞（锚点=首拍 0）

        // 在飞 12s：跨过名义网格 5/10 两拍——结算后推进到首个未来网格点 15s，不补跑
        nanoClock.addAndGet(12L * SECOND_NANOS);
        pending.complete(Boolean.TRUE);
        assertEquals("结算后重排下一拍", 2, timers.shots.size());
        assertEquals("fixedRate 跳过在飞期间跨过的拍（12s 结算 → 下一拍在 15s 网格点，等 3s）",
                3_000L, timers.shots.get(1).delayMillis);
        handle.cancel();
    }

    @Test
    public void staleShotDropsRoundBody_andRearmsToFutureGrid() throws Exception {
        AtomicInteger roundInvocations = new AtomicInteger();
        PollingHandle handle = ModbusPolling.on(host, newSource(2000))
                .round(src -> {
                    roundInvocations.incrementAndGet();
                    return CompletableFuture.completedFuture(Boolean.TRUE);
                })
                .every(5, TimeUnit.SECONDS)
                .fixedRate()
                .withNanoClock(nanoClock::get)
                .start();
        timers.fire(0); // 第 1 轮立即执行+结算 → 下一拍排在名义网格 +5s
        assertEquals("前置：首轮已执行", 1, roundInvocations.get());

        // 到拍滞后：钟推进 12s 后才到拍（模拟定时线程饱和）——滞后 7s > 周期 5s → 过期即弃
        nanoClock.addAndGet(12L * SECOND_NANOS);
        timers.fire(1);
        assertEquals("过期拍不得触碰轮体（本轮数据无意义）", 1, roundInvocations.get());
        assertEquals("弃拍后重排到首个未来网格点（名义锚点 5s 已被跨过 → 推进 skips=7/5+1=2 格到 15s，钟在 12s → 等 3s）",
                3_000L, timers.shots.get(2).delayMillis);
        assertTrue("弃拍不是失败：轮询必须仍在运行", handle.isRunning());
        handle.cancel();
    }

    // ==================== delay(ms) 留隙糖（B 族收编备用） ====================

    @Test
    public void delaySugarCompletesOnShotFire() throws Exception {
        ModbusPolling polling = ModbusPolling.on(host, newSource(2000));
        int shotsBefore = timers.shots.size();
        CompletableFuture<Void> gap = polling.delay(50);

        assertEquals("delay(ms) 必须经 SDK 定时器提交一个单发", shotsBefore + 1, timers.shots.size());
        assertEquals("单发延迟=ms", 50L, timers.lastShot().delayMillis);
        assertFalse("到拍前 CF 不得完成", gap.isDone());

        timers.fire(timers.shots.size() - 1);
        assertTrue("到拍后 CF 必须以 null 完成", gap.isDone());
        gap.get(1, TimeUnit.SECONDS); // 无异常完成（strict：取值抛错即失败）
    }

    @Test
    public void delaySugarRejectsNonPositiveMillis() {
        ModbusPolling polling = ModbusPolling.on(host, newSource(2000));
        try {
            polling.delay(0);
            fail("delay(0) 必须 fail-fast（零留隙无意义，别调 delay）");
        } catch (IllegalArgumentException expected) { }
        try {
            polling.delay(-1);
            fail("delay(负) 必须 fail-fast");
        } catch (IllegalArgumentException expected) { }
    }

    // ==================== 轮体契约（任何终态都重排——永不注销） ====================

    @Test
    public void lockBusyRoundIsDigested_andRearmsOnNominalGrid() throws Exception {
        ModbusSource source = newSource(2000);
        String held = source.acquire(1, TimeUnit.SECONDS);
        assertNotNull("前置：测试线程持锁", held);

        AtomicInteger roundInvocations = new AtomicInteger();
        List<RoundReport> reports = new CopyOnWriteArrayList<>();
        PollingHandle handle = ModbusPolling.on(host, source)
                .round(src -> {
                    roundInvocations.incrementAndGet();
                    return CompletableFuture.completedFuture(Boolean.TRUE);
                })
                .every(5, TimeUnit.SECONDS)
                .onRound(reports::add)
                .withNanoClock(nanoClock::get)
                .start();
        timers.fire(0); // 首轮：锁忙 → LockBusySkipped 异常 future（同步完成）

        assertEquals("锁被外部持有时 round 体不得执行", 0, roundInvocations.get());
        assertEquals("锁忙轮必须报告 LOCK_BUSY_SKIPPED（SDK 内部消化语义可观测）",
                RoundReport.Outcome.LOCK_BUSY_SKIPPED, reports.get(reports.size() - 1).getOutcome());
        assertEquals("锁忙跳拍后照常重排下一拍（网格不变）", 2, timers.shots.size());
        assertEquals("锁忙跳拍必须计入源级记账（禁静默）", 1L, source.getLockBusySkipCount());
        assertTrue(source.release(held));
        handle.cancel();
    }

    @Test
    public void businessFalseAndTransmissionFailureRearm_neverUnregister() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        List<RoundReport> reports = new CopyOnWriteArrayList<>();
        PollingHandle handle = ModbusPolling.on(host, newSource(2000))
                .round(src -> {
                    switch (invocations.incrementAndGet()) {
                        case 1:
                            return CompletableFuture.completedFuture(Boolean.FALSE); // 业务 false
                        case 2: {
                            CompletableFuture<Boolean> failed = new CompletableFuture<>();
                            failed.completeExceptionally(new java.io.IOException("io-boom"));
                            return failed; // 传输错误
                        }
                        default:
                            // round 体同步抛：策略内 catch→release+异常完成（FAILED 轮），
                            // 链层 begin-throw 兜底由 core PeriodicChainTest 覆盖
                            throw new IllegalStateException("round-body-boom");
                    }
                })
                .every(5, TimeUnit.SECONDS)
                .onRound(reports::add)
                .withNanoClock(nanoClock::get)
                .start();

        timers.fire(0); // 第 1 轮：业务 false
        assertEquals("业务 false 报告 BUSINESS_FALSE", RoundReport.Outcome.BUSINESS_FALSE,
                reports.get(reports.size() - 1).getOutcome());
        timers.fire(1); // 第 2 轮：传输错误
        assertEquals("传输错误报告 FAILED（携带根因）", RoundReport.Outcome.FAILED,
                reports.get(reports.size() - 1).getOutcome());
        assertNotNull("FAILED 报告必须携带原始异常",
                reports.get(reports.size() - 1).getError());
        timers.fire(2); // 第 3 轮：round 体同步抛（策略内 catch→release+FAILED 轮）
        assertEquals("三连异常/失败后仍必须重排（永不注销）", 4, timers.shots.size());
        assertTrue("全程轮询存活", handle.isRunning());
        handle.cancel();
    }

    // ==================== 宿主绑定 + cancel ====================

    @Test
    public void removalBindingRegistersExactlyOneAction_andCancelsPendingShot() throws Exception {
        PollingHandle handle = readyPolling(newSource(2000),
                () -> CompletableFuture.completedFuture(Boolean.TRUE)).start();
        timers.fire(0); // 首轮结算 → 下一拍已排在 shots[1]
        assertEquals("start() 必须向宿主注册恰好一条移除动作（SDK 内绑）",
                1, removalActions.size());

        removalActions.get(0).run(); // 模拟设备移除 sweep
        assertFalse("移除动作执行后轮询必须停", handle.isRunning());
        assertTrue("cancel 必须撤销当前待发单发",
                ((FakeModbusTimers.StubFuture) timers.shots.get(1).future).cancelled);
    }

    // ==================== 构建契约（严格模式 fail-fast） ====================

    @Test
    public void withNanoClockRejectsNull() {
        try {
            ModbusPolling.on(host, newSource(2000)).withNanoClock(null);
            fail("withNanoClock(null) 必须 fail-fast");
        } catch (IllegalArgumentException expected) { }
    }
}
