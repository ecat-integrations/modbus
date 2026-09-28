package com.ecat.integration.ModbusIntegration.Sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ecat.core.Device.RemovalHost;
import com.ecat.integration.ModbusIntegration.ModbusProtocol;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.ModbusTcpInfo;

/**
 * {@link ModbusPolling#roundChain()} 多段轮契约（fake 定时缝 + 注入纳米钟 + 真实锁状态机
 * source，与 ModbusPollingChainTest 同构）：
 * <ul>
 *   <li>修复语义：段间留隙在源锁临界区之外——写者（校准写等外部使用者）可在留隙窗内
 *       取锁；对照面是单段 round()（体内 delay 整轮持锁，单事务契约存档用例）；</li>
 *   <li>段契约：每段是独立源锁事务（独立预算获取、独立硬超时、独立释放）；段体
 *       false/异常 ⇒ 中止不追读后续段，轮结局=该段结局（V2 机型「块二解析失败不追
 *       块三」的门语义由该折叠规则承载）；</li>
 *   <li>结局分类兼容：单报告/轮，五结局语义与单段 round() 完全一致
 *       （LOCK_BUSY_SKIPPED 照常入源级记账；已结算段的数据照常保留，无回滚）。</li>
 * </ul>
 * 无 Thread.sleep：锁忙预算耗尽用例沿用链测试约定（真实 ~500ms FIFO 预算 + 事件闩）。
 */
public class ModbusPollingRoundChainTest {

    private FakeModbusTimers timers;
    private RemovalHost host;
    private AtomicLong nanoClock;

    @Before
    public void setUp() {
        timers = new FakeModbusTimers();
        host = action -> { };
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

    /** 重排事件等待（deadline 轮询，超时即失败）——链测试同款。 */
    private void awaitShotCount(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (timers.shots.size() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("下一拍未在 5s 内重排落账: expected>=" + expected
                        + ", actual=" + timers.shots.size());
            }
        }
    }

    // ==================== 修复主断言：段间留隙在锁外 ====================

    /**
     * 修复语义核心：两段轮的段间留隙期间源锁空闲——写者立即取走；写者持锁横跨 gap
     * 到点后释放，段二重新取锁继续执行，整轮单报告 SUCCESS 收尾、下一拍照常重排。
     */
    @Test
    public void gapBetweenSegmentsIsLockFree_writerAcquiresAndSecondSegmentContinues() throws Exception {
        ModbusSource source = newSource(2000);
        List<RoundReport> reports = new CopyOnWriteArrayList<>();
        CountDownLatch roundSettled = new CountDownLatch(1);

        PollingHandle handle = ModbusPolling.on(host, source)
                .roundChain()
                .held(src -> CompletableFuture.completedFuture(Boolean.TRUE))
                .gap(1000L)
                .held(src -> CompletableFuture.completedFuture(Boolean.TRUE))
                .end()
                .every(5, TimeUnit.SECONDS)
                .onRound(report -> {
                    reports.add(report);
                    roundSettled.countDown();
                })
                .withNanoClock(nanoClock::get)
                .start();

        timers.fire(0); // 段一快路径取锁→段体→释放→gap 单发登记，段体为预完成 CF 时全部同步落定
        assertEquals("段间留隙必须以 SDK 单发承载", 2, timers.shots.size());
        assertEquals("gap 单发延迟=声明值", 1000L, timers.shots.get(1).delayMillis);

        // 修复断言：留隙在临界区外——写者立即取锁（缺陷形态：整轮单事务持锁时写者预算耗尽）
        String writerKey = source.acquire(200, TimeUnit.MILLISECONDS);
        assertNotNull("段间留隙期间源锁必须空闲（写者可立即取锁）", writerKey);

        timers.fire(1); // 写者持锁时 gap 到点：段二发起取锁（排队或快路径均合法），写者释放后继续
        assertTrue(source.release(writerKey));
        assertTrue("段二必须在写者释放后获锁执行、整轮 SUCCESS 落账（轮结算蕴含段二已跑）",
                roundSettled.await(5, TimeUnit.SECONDS));
        assertEquals("两段全 true → 本轮单报告 SUCCESS", RoundReport.Outcome.SUCCESS,
                reports.get(reports.size() - 1).getOutcome());
        assertEquals(1L, handle.getCompletedRounds());
        awaitShotCount(3); // 折叠轮结算后下一拍照常重排
        handle.cancel();
    }

    /**
     * 对照基线（默认路径契约存档，实现后仍须绿）：单段 round() 体内经 delay(ms) 留隙
     * 是同一个源锁事务——留隙期间锁被持有，写者取不到；轮结算释放后写者恢复可取。
     * 这是 round() 单事务语义的既有设计（默认路径不动）；roundChain() 的修复=提供
     * 临界区外留隙的新词汇，而非改变 round() 行为。
     */
    @Test
    public void singleRoundHoldsSourceLockAcrossInternalDelay_singleTransactionContract() throws Exception {
        ModbusSource source = newSource(2000);
        ModbusPolling polling = ModbusPolling.on(host, source);
        PollingHandle handle = polling
                .round(src -> CompletableFuture.completedFuture(Boolean.TRUE)
                        .thenCompose(v -> polling.delay(1000L))
                        .thenCompose(v -> CompletableFuture.completedFuture(Boolean.TRUE)))
                .every(5, TimeUnit.SECONDS)
                .withNanoClock(nanoClock::get)
                .start();

        timers.fire(0); // 段体同步完成、round 在 1s delay 上挂起——事务在飞、锁持有
        String writerKey = source.acquire(200, TimeUnit.MILLISECONDS);
        assertNull("单事务 round 的体内留隙在锁内（对照契约：写者此窗不可得锁）", writerKey);

        timers.fire(1); // delay 到点 → 段体收尾 → 释放 → 下一拍重排，全部同步落定
        awaitShotCount(2);
        String writerKeyAfter = source.acquire(200, TimeUnit.MILLISECONDS);
        assertNotNull("轮结算释放后写者恢复可取锁", writerKeyAfter);
        assertTrue(source.release(writerKeyAfter));
        handle.cancel();
    }

    // ==================== 锁忙跳拍：分类兼容 + 数据保留 ====================

    /**
     * 段二取锁预算耗尽（写者长事务形态）：本轮按 LOCK_BUSY_SKIPPED 收尾并入源级记账，
     * 已结算段（段一）的数据照常保留不回滚，段体未执行，下一拍照常重排。
     */
    @Test
    public void segmentBeyondLockBudgetSkipsRound_firstSegmentDataRetained() throws Exception {
        ModbusSource source = newSource(2000);
        AtomicBoolean firstSegmentDataKept = new AtomicBoolean(false);
        AtomicBoolean secondSegmentRan = new AtomicBoolean(false);
        List<RoundReport> reports = new CopyOnWriteArrayList<>();
        CountDownLatch roundSettled = new CountDownLatch(1);

        PollingHandle handle = ModbusPolling.on(host, source)
                .roundChain()
                .held(src -> {
                    firstSegmentDataKept.set(true);
                    return CompletableFuture.completedFuture(Boolean.TRUE);
                })
                .gap(1000L)
                .held(src -> {
                    secondSegmentRan.set(true);
                    return CompletableFuture.completedFuture(Boolean.TRUE);
                })
                .end()
                .every(5, TimeUnit.SECONDS)
                .onRound(report -> {
                    reports.add(report);
                    roundSettled.countDown();
                })
                .withNanoClock(nanoClock::get)
                .start();

        timers.fire(0); // 段一结算（数据已注入），gap 单发登记
        String writerKey = source.acquire(200, TimeUnit.MILLISECONDS); // 写者在留隙窗取锁且不释放
        assertNotNull(writerKey);

        timers.fire(1); // 段二到点：锁被写者持 → 有界等待（预算 500ms）耗尽 → 弃轮（IO 旁池线程落账）
        assertTrue("段二预算耗尽须按 LOCK_BUSY_SKIPPED 收尾", roundSettled.await(5, TimeUnit.SECONDS));
        assertTrue("已结算段数据照常保留（不因后续段跳拍回滚）", firstSegmentDataKept.get());
        assertFalse("跳拍段的段体不得执行", secondSegmentRan.get());
        assertEquals(RoundReport.Outcome.LOCK_BUSY_SKIPPED, reports.get(reports.size() - 1).getOutcome());
        assertEquals("真弃轮必须计入源级记账（禁静默）", 1L, source.getLockBusySkipCount());
        assertEquals("锁忙轮不计成功轮", 0L, handle.getCompletedRounds());
        awaitShotCount(3); // 弃轮后下一拍照常重排
        assertTrue(source.release(writerKey));
        handle.cancel();
    }

    // ==================== 折叠规则：中段失败不追读，轮结局=末段结局 ====================

    /**
     * 中段业务 false：不追读后续段（后续 gap 单发不再登记），轮结局 BUSINESS_FALSE、
     * 不计成功轮——V2 机型「块二解析失败则不追块三」的门语义由该折叠规则承载。
     */
    @Test
    public void midSegmentFalseSkipsRemainingSegments_roundEndsBusinessFalse() {
        ModbusSource source = newSource(2000);
        AtomicBoolean thirdSegmentRan = new AtomicBoolean(false);
        List<RoundReport> reports = new CopyOnWriteArrayList<>();

        PollingHandle handle = ModbusPolling.on(host, source)
                .roundChain()
                .held(src -> CompletableFuture.completedFuture(Boolean.TRUE))
                .gap(1000L)
                .held(src -> CompletableFuture.completedFuture(Boolean.FALSE)) // 中段业务失败
                .gap(1000L)
                .held(src -> {
                    thirdSegmentRan.set(true);
                    return CompletableFuture.completedFuture(Boolean.TRUE);
                })
                .end()
                .every(5, TimeUnit.SECONDS)
                .onRound(reports::add)
                .withNanoClock(nanoClock::get)
                .start();

        timers.fire(0); // 段一 true → gap1 单发登记
        assertEquals(2, timers.shots.size());
        timers.fire(1); // 段二 false → 折叠中止：不追读段三、不再登记 gap2，仅剩下一拍重排
        assertEquals("折叠中止后不得登记后续 gap（段三不追读），仅剩下一拍重排",
                3, timers.shots.size());
        assertFalse("中段失败后不得追读后续段", thirdSegmentRan.get());
        assertEquals(RoundReport.Outcome.BUSINESS_FALSE, reports.get(reports.size() - 1).getOutcome());
        assertEquals("业务失败轮不计成功轮", 0L, handle.getCompletedRounds());
        assertTrue(handle.isRunning());
        handle.cancel();
    }

    /** 末段业务 false：轮结局 BUSINESS_FALSE，已结算段数据照常保留、不回滚。 */
    @Test
    public void lastSegmentFalseReportsBusinessFalse_firstSegmentDataRetained() {
        ModbusSource source = newSource(2000);
        AtomicBoolean firstSegmentDataKept = new AtomicBoolean(false);
        List<RoundReport> reports = new CopyOnWriteArrayList<>();

        PollingHandle handle = ModbusPolling.on(host, source)
                .roundChain()
                .held(src -> {
                    firstSegmentDataKept.set(true);
                    return CompletableFuture.completedFuture(Boolean.TRUE);
                })
                .gap(1000L)
                .held(src -> CompletableFuture.completedFuture(Boolean.FALSE))
                .end()
                .every(5, TimeUnit.SECONDS)
                .onRound(reports::add)
                .withNanoClock(nanoClock::get)
                .start();

        timers.fire(0); // 段一结算（数据已注入），gap 单发登记
        assertTrue(firstSegmentDataKept.get());
        timers.fire(1); // 末段 false → 轮 BUSINESS_FALSE → 结算重排，同步落定
        assertEquals(RoundReport.Outcome.BUSINESS_FALSE, reports.get(reports.size() - 1).getOutcome());
        assertEquals(0L, handle.getCompletedRounds());
        assertEquals("已结算段数据不回滚；下一拍照常重排", 3, timers.shots.size());
        handle.cancel();
    }

    /** 段体传输异常：轮结局 FAILED（携带根因）、折叠中止，轮询照常存活并重排。 */
    @Test
    public void segmentFailureReportsFailedAndAbortsChain() {
        ModbusSource source = newSource(2000);
        AtomicBoolean laterSegmentRan = new AtomicBoolean(false);
        List<RoundReport> reports = new CopyOnWriteArrayList<>();

        PollingHandle handle = ModbusPolling.on(host, source)
                .roundChain()
                .held(src -> CompletableFuture.completedFuture(Boolean.TRUE))
                .gap(1000L)
                .held(src -> {
                    CompletableFuture<Boolean> failed = new CompletableFuture<>();
                    failed.completeExceptionally(new IOException("io-boom"));
                    return failed;
                })
                .gap(1000L)
                .held(src -> {
                    laterSegmentRan.set(true);
                    return CompletableFuture.completedFuture(Boolean.TRUE);
                })
                .end()
                .every(5, TimeUnit.SECONDS)
                .onRound(reports::add)
                .withNanoClock(nanoClock::get)
                .start();

        timers.fire(0);
        assertEquals(2, timers.shots.size());
        timers.fire(1); // 段二异常 → 折叠中止（段三不追读）→ FAILED 报告 → 重排
        assertEquals(RoundReport.Outcome.FAILED, reports.get(reports.size() - 1).getOutcome());
        assertNotNull("FAILED 报告必须携带原始异常", reports.get(reports.size() - 1).getError());
        assertFalse("异常中止后不得追读后续段", laterSegmentRan.get());
        assertEquals(3, timers.shots.size());
        assertTrue("全程轮询存活（永不注销）", handle.isRunning());
        handle.cancel();
    }

    // ==================== 按段独立硬超时 ====================

    /**
     * 段一挂死（传输挂死形态）：硬超时预算按段独立（requestTimeout×6，非全轮共享），
     * 到点异常完成 → TIMED_OUT 收尾 → 折叠中止、锁必归还、下一拍照常重排。
     */
    @Test
    public void eachSegmentCarriesItsOwnHardTimeout_timeoutAbortsChainAndReleasesLock() {
        ModbusSource source = newSource(50); // 每段硬超时 = 50×6 = 300ms
        AtomicBoolean secondSegmentRan = new AtomicBoolean(false);
        List<RoundReport> reports = new CopyOnWriteArrayList<>();

        PollingHandle handle = ModbusPolling.on(host, source)
                .roundChain()
                .held(src -> new CompletableFuture<Boolean>()) // 段一挂死
                .gap(1000L)
                .held(src -> {
                    secondSegmentRan.set(true);
                    return CompletableFuture.completedFuture(Boolean.TRUE);
                })
                .end()
                .every(5, TimeUnit.SECONDS)
                .onRound(reports::add)
                .withNanoClock(nanoClock::get)
                .start();

        timers.fire(0); // 段一在飞：apply 看门狗 + 按段硬超时两笔事务内建计时已登记
        assertEquals("段一登记两笔事务内建计时（apply 看门狗 + 按段硬超时）",
                2, timers.transactionInternalShots.size());
        assertTrue("apply 看门狗已自取消（段体立即返回引用，未阻塞）",
                timers.transactionInternalShots.get(0).future.isCancelled());
        FakeModbusTimers.Shot hardTimeoutShot = timers.transactionInternalShots.get(1);
        assertFalse("硬超时计时在飞", hardTimeoutShot.future.isCancelled());
        assertEquals("硬超时预算按段独立 = requestTimeout×6 = 300ms（全轮共享制应为 600ms）",
                300L, hardTimeoutShot.delayMillis);

        hardTimeoutShot.command.run(); // 段一硬超时到点 → 异常完成 → release 必执行（同步落定）
        assertEquals("挂死段按 TIMED_OUT 收尾", RoundReport.Outcome.TIMED_OUT,
                reports.get(reports.size() - 1).getOutcome());
        assertFalse("超时中止折叠：后续段不得追读", secondSegmentRan.get());
        String writerKey = source.acquire(200, TimeUnit.MILLISECONDS);
        assertNotNull("硬超时段的锁必须已归还（幽灵锁零容忍）", writerKey);
        assertTrue(source.release(writerKey));
        awaitShotCount(2); // 下一拍照常重排
        assertTrue(handle.isRunning());
        handle.cancel();
    }

    // ==================== 构建契约（严格模式 fail-fast） ====================

    /** roundChain 声明序与互斥规则：非法序列一律 fail-fast，不带病进入 start()。 */
    @Test
    public void roundChainBuilderContractFailFast() {
        ModbusSource source = newSource(2000);
        Function<ModbusSource, CompletableFuture<Boolean>> ok =
                src -> CompletableFuture.completedFuture(Boolean.TRUE);

        // 空链 end() → ISE（至少一个 held）
        try {
            ModbusPolling.on(host, source).roundChain().end();
            fail("end() 前无任何 held 必须 fail-fast");
        } catch (IllegalStateException expected) { }

        // gap 在 held 前 → ISE
        try {
            ModbusPolling.on(host, source).roundChain().gap(100L);
            fail("gap 必须跟在 held 之后");
        } catch (IllegalStateException expected) { }

        // 重复 gap → ISE
        try {
            ModbusPolling.on(host, source).roundChain().held(ok).gap(1L).gap(1L);
            fail("重复 gap 必须 fail-fast");
        } catch (IllegalStateException expected) { }

        // 尾 gap 无所属段 → ISE（end 时 gap 数 != 段数-1）
        try {
            ModbusPolling.on(host, source).roundChain().held(ok).gap(1L).held(ok).gap(1L).end();
            fail("尾随 gap 无所属段必须 fail-fast");
        } catch (IllegalStateException expected) { }

        // gap 非正 → IAE（零留隙无意义，别调 gap）
        try {
            ModbusPolling.on(host, source).roundChain().held(ok).gap(0L);
            fail("gap(0) 必须 fail-fast");
        } catch (IllegalArgumentException expected) { }
        try {
            ModbusPolling.on(host, source).roundChain().held(ok).gap(-1L);
            fail("gap(负) 必须 fail-fast");
        } catch (IllegalArgumentException expected) { }

        // held(null) → IAE
        try {
            ModbusPolling.on(host, source).roundChain().held(null);
            fail("held(null) 必须 fail-fast");
        } catch (IllegalArgumentException expected) { }

        // round() 与 roundChain() 互斥（双向）
        try {
            ModbusPolling.on(host, source).round(ok).roundChain();
            fail("round() 已声明后再 roundChain() 必须 fail-fast");
        } catch (IllegalStateException expected) { }
        ModbusPolling chainPolling = ModbusPolling.on(host, source);
        chainPolling.roundChain().held(ok).end();
        try {
            chainPolling.round(ok);
            fail("roundChain() 已声明后再 round() 必须 fail-fast");
        } catch (IllegalStateException expected) { }

        // 未声明轮体（round 与 chain 皆无）start() → ISE
        try {
            ModbusPolling.on(host, source).every(1, TimeUnit.SECONDS).start();
            fail("start() 前必须声明 round 或 roundChain");
        } catch (IllegalStateException expected) { }
    }
}
