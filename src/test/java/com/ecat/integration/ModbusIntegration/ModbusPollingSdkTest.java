package com.ecat.integration.ModbusIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Test;

import com.ecat.core.Device.RemovalHost;
import com.ecat.core.Utils.TestTools;
import com.ecat.integration.ModbusIntegration.Sdk.ModbusPolling;
import com.ecat.integration.ModbusIntegration.Sdk.PollingHandle;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.msg.ModbusRequest;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersResponse;

/**
 * {@link ModbusPolling}（modbus 域轮询 SDK）五维契约 + 同源 FIFO 组合（17 号 §2.1 轮询
 * 模式的 modbus 形态）：
 * <ol>
 *   <li>周期：fixedDelay 完成点语义——事务 CF 在飞时下一轮不提前发射，完成后按完成点+period；</li>
 *   <li>锁：源锁忙轮 LockBusySkippedException 内部消化（报告 LOCK_BUSY_SKIPPED、周期链
 *       正常完成），锁释放后下一拍恢复；</li>
 *   <li>超时：事务内建硬超时（requestTimeoutMs×6）到点报 TIMED_OUT，轮询不停；</li>
 *   <li>异常韧性：同步抛/异步失败/业务 false 各归各类（FAILED/BUSINESS_FALSE），周期任务
 *       永不注销；</li>
 *   <li>组合：SDK 周期 × dispatchIo 同源 FIFO 单飞——两轮 round 间同源帧串行不并发；</li>
 *   <li>宿主绑定：on(host, source) 工厂内绑——start() 向 RemovalHost 注册移除动作，
 *       动作执行（设备移除 sweep）轮询即停。</li>
 * </ol>
 * 边界：SDK 域自持默认定时池（{@code ModbusSdkTimers}，29 号 v2 S1——真实 STPE 驱动，
 * 轮询发起即返），真实 ModbusSource 锁状态机（skipOpen 不建 master，零网络；组合用例经
 * TestTools 注入 mock master）；确定性同步 = latch/窗口断言，无 Thread.sleep。网格数学的
 * 确定性逐拍验证见 Sdk 包 {@code ModbusPollingChainTest}（fake 定时缝+假钟零真实时钟）。
 */
public class ModbusPollingSdkTest {

    private static final long PERIOD_MS = 150L;
    /** 观察窗 > 发起段重排最早下一发（period 150 + 调度余量）；事务 CF 窗内恒被测试持有。 */
    private static final long NO_EARLY_FIRE_WINDOW_MS = 350L;
    private static final long AWAIT_MS = 5_000L;

    /** 收集型假宿主（18 号 §3.3 测试缝）：断言 SDK 内绑的移除动作条数与执行效果。 */
    private final List<Runnable> removalActions = new CopyOnWriteArrayList<>();
    private final RemovalHost host = removalActions::add;

    @After
    public void tearDown() {
        // 组合用例经共享 IO 旁池，测试基建复位（shutdown 已是终端态——复位重开后续类的
        // 惰性建池窗口），与 ModbusIoPoolDispatchTest 同收尾。
        // SDK 定时池不在此关停：本套件即消费它的真实池形态（跨类共享，daemon 不阻 JVM 退出）
        ModbusIoPool.resetForTest();
    }

    /** 真实锁状态机 source（skipOpen 不建 master 不碰网络；closeModbus 空实现同测试边界）。 */
    private ModbusSource newSource(int requestTimeoutMs) {
        return new ModbusSource(
                new ModbusTcpInfo("127.0.0.1", 19999, 1, ModbusProtocol.TCP, requestTimeoutMs),
                1, 500, true, false) {
            @Override
            public void closeModbus() {
            }
        };
    }

    /** ①周期（fixedDelay 完成点语义）：事务在飞不提前发射；完成后按完成点+period 发射。 */
    @Test
    public void roundCompletionDrivesNextRound_fixedDelaySemantics() throws Exception {
        ModbusSource source = newSource(2000);
        CountDownLatch roundOne = new CountDownLatch(1);
        CountDownLatch roundTwo = new CountDownLatch(1);
        AtomicReference<CompletableFuture<Boolean>> firstTx = new AtomicReference<>();
        PollingHandle handle = ModbusPolling.on(host, source)
                .round(src -> {
                    // 首轮事务 CF 由测试持有（在飞）；后续轮即时完成
                    CompletableFuture<Boolean> tx = new CompletableFuture<>();
                    if (firstTx.compareAndSet(null, tx)) {
                        roundOne.countDown();
                        return tx;
                    }
                    roundTwo.countDown();
                    return CompletableFuture.completedFuture(Boolean.TRUE);
                })
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .start();

        assertTrue("首轮（发起段）必须执行", roundOne.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertFalse("事务 CF 在飞期间不得按「发起段返回 + period」提前发射下一轮",
                roundTwo.await(NO_EARLY_FIRE_WINDOW_MS, TimeUnit.MILLISECONDS));

        firstTx.get().complete(Boolean.TRUE);
        assertTrue("事务完成后下一轮必须发射（事务完成点 + period）",
                roundTwo.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        handle.cancel();
    }

    // ==================== 断连状态转移行：首败 WARN / 恢复 INFO / 去重 ====================

    /**
     * comm 熔断退役（W3）的补偿观测（与 SerialPolling 同型语义）：连续失败轮（传输异常/
     * 超时/业务 false）只在首败打一行 WARN「link DOWN」，断连后首个 SUCCESS 轮打一行
     * INFO「link RECOVERED」——锁忙轮（LOCK_BUSY_SKIPPED）是内部跳过信号，不改断连态。
     */
    @Test
    public void consecutiveFailuresLogOneDownLine_thenOneRecoveryLine() throws Exception {
        ModbusSource source = newSource(2000);
        ch.qos.logback.classic.Logger pollingLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ModbusPolling.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        pollingLogger.addAppender(appender);
        // 摘下 ecat-core logback.xml 的 ErrorRateLimitFilter（生产 WARN/ERROR 3s 限频策略，
        // 有独立测试）——本测断言 SDK 自身发射语义，须在无限频通道上观察，用毕恢复
        ch.qos.logback.classic.LoggerContext ctx =
                (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        java.util.List<ch.qos.logback.classic.turbo.TurboFilter> savedTurbo =
                new java.util.ArrayList<>(ctx.getTurboFilterList());
        ctx.getTurboFilterList().clear();
        try {
            AtomicBoolean failing = new AtomicBoolean(true);
            CountDownLatch threeFailures = new CountDownLatch(3);
            CountDownLatch successRound = new CountDownLatch(1);

            PollingHandle handle = ModbusPolling.on(host, source)
                    .round(src -> {
                        if (failing.get()) {
                            threeFailures.countDown(); // 失败轮发起（onRound 观测面零消费已删，round 体即观测点）
                            CompletableFuture<Boolean> failed = new CompletableFuture<>();
                            failed.completeExceptionally(new IllegalStateException("sim link down"));
                            return failed;
                        }
                        successRound.countDown();
                        return CompletableFuture.completedFuture(Boolean.TRUE);
                    })
                    .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                    .start();

            assertTrue("前置：3 个失败轮必须发生", threeFailures.await(AWAIT_MS, TimeUnit.MILLISECONDS));
            failing.set(false);
            assertTrue("恢复轮必须发生", successRound.await(AWAIT_MS, TimeUnit.MILLISECONDS));

            long downLines = appender.list.stream()
                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .filter(e -> e.getFormattedMessage().contains("link DOWN"))
                    .count();
            long recoveredLines = appender.list.stream()
                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO)
                    .filter(e -> e.getFormattedMessage().contains("link RECOVERED"))
                    .count();
            long errorLines = appender.list.stream()
                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
                    .count();
            assertEquals("连续失败期 DOWN 行恰一条（去重，首败一次）", 1L, downLines);
            assertEquals("恢复行恰一条（首个成功轮）", 1L, recoveredLines);
            assertTrue("per-round ERROR 全栈照打（每失败轮一条，实得 " + errorLines + "）",
                    errorLines >= 3L);
            handle.cancel();
        } finally {
            ctx.getTurboFilterList().addAll(savedTurbo);
            pollingLogger.detachAppender(appender);
        }
    }

    /**
     * ③锁（LockBusy 内部消化）：外持锁轮被跳过（round 体不执行、源级锁忙记账递增）、
     * 链侧正常完成（isRunning 不变），释放后恢复执行。
     */
    @Test
    public void lockBusyRoundsAreDigestedAndReported_resumeAfterRelease() throws Exception {
        ModbusSource source = newSource(2000);
        String held = source.acquire(1, TimeUnit.SECONDS);
        assertNotNull("前置：测试线程持锁", held);
        AtomicInteger roundInvocations = new AtomicInteger();
        CountDownLatch successSeen = new CountDownLatch(1);
        PollingHandle handle = ModbusPolling.on(host, source)
                .round(src -> {
                    roundInvocations.incrementAndGet();
                    successSeen.countDown();
                    return CompletableFuture.completedFuture(Boolean.TRUE);
                })
                .every(100, TimeUnit.MILLISECONDS)
                .start();

        // 锁忙跳拍的观测点 = 源级记账（onRound 零消费已删；确定性条件自旋验证事件已发生）
        awaitTrue("外持锁期间至少一轮锁忙跳拍（源级记账）",
                () -> source.getLockBusySkipCount() >= 1, AWAIT_MS);
        assertEquals("锁被外部持有时 round 体不得执行", 0, roundInvocations.get());
        assertTrue("锁忙跳拍不是失败，轮询必须仍在运行", handle.isRunning());

        assertTrue(source.release(held));
        assertTrue("锁释放后下一拍必须成功执行", successSeen.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertEquals(1, roundInvocations.get());
        handle.cancel();
    }

    /** awaitility 式有界条件自旋：验证「条件已成立」，超时即失败（非固定 sleep 猜测）。 */
    private static void awaitTrue(String what, java.util.function.BooleanSupplier condition, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                fail("condition not met within " + timeoutMs + "ms: " + what);
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while awaiting: " + what);
            }
        }
    }

    /**
     * ④超时（事务内建硬超时；SDK timeoutMs 收紧词汇零消费已删）：首轮事务 CF 永不完成
     * （模拟传输挂死），内建硬超时（requestTimeoutMs×6=300ms）到点异常完成该轮并释放
     * 源锁，后续轮照常成功——超时不是死刑。
     */
    @Test
    public void roundTimeoutReportsTimedOut_andPollingContinuesAfterRecovery() throws Exception {
        ModbusSource source = newSource(50); // 内建硬超时 = 50ms×6 = 300ms
        AtomicInteger invocations = new AtomicInteger();
        PollingHandle handle = ModbusPolling.on(host, source)
                .round(src -> invocations.incrementAndGet() == 1
                        // 首轮事务 CF 永不完成（传输挂死形态）；后续轮即时成功
                        ? new CompletableFuture<>()
                        : CompletableFuture.completedFuture(Boolean.TRUE))
                .every(100, TimeUnit.MILLISECONDS)
                .start();

        // 无回调观测面（onRound 已删）：超时轮的可见形态 = 第 2 轮 round 体得以执行
        // （第 1 轮挂死时源锁被占，第 2 轮只能在硬超时释放锁后发起）
        awaitTrue("事务硬超时释放锁后，后续轮必须发起（超时不是死刑）",
                () -> invocations.get() >= 2, AWAIT_MS);
        assertTrue("超时后轮询必须仍在运行", handle.isRunning());
        handle.cancel();
    }

    /** ⑤异常韧性：同步抛/异步失败/业务 false 逐轮分类（FAILED/BUSINESS_FALSE），周期永不注销。 */
    @Test
    public void failuresAreClassifiedAndPollingNeverUnregisters() throws Exception {
        ModbusSource source = newSource(2000);
        AtomicInteger invocations = new AtomicInteger();
        CountDownLatch twoFailures = new CountDownLatch(2);
        CountDownLatch businessFalseSeen = new CountDownLatch(1);
        CountDownLatch successSeen = new CountDownLatch(1);
        PollingHandle handle = ModbusPolling.on(host, source)
                .round(src -> {
                    switch (invocations.incrementAndGet()) {
                        case 1:
                            twoFailures.countDown(); // begin 同步抛（onRound 观测面零消费已删，round 体即观测点）
                            throw new IllegalStateException("begin-boom");
                        case 2: {
                            twoFailures.countDown();
                            CompletableFuture<Boolean> failed = new CompletableFuture<>();
                            failed.completeExceptionally(new java.io.IOException("io-boom"));
                            return failed;
                        }
                        case 3:
                            businessFalseSeen.countDown();
                            return CompletableFuture.completedFuture(Boolean.FALSE);
                        default:
                            successSeen.countDown();
                            return CompletableFuture.completedFuture(Boolean.TRUE);
                    }
                })
                .every(100, TimeUnit.MILLISECONDS)
                .start();

        assertTrue("同步抛与异步失败轮必须都发起", twoFailures.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertTrue("业务 false 轮必须发起（统一 warn，不中断轮询）",
                businessFalseSeen.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertTrue("周期永不注销：失败/false 之后仍能成功轮",
                successSeen.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertTrue("全程轮询存活", handle.isRunning());
        handle.cancel();
    }

    /** ⑥构建契约（严格模式 fail-fast）：缺 round / 缺 every 必须显式拒绝。 */
    @Test
    public void startWithoutRoundOrEveryFailsFast() {
        ModbusSource source = newSource(2000);
        try {
            ModbusPolling.on(host, source).every(1, TimeUnit.SECONDS).start();
            fail("缺 round 必须 fail-fast");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("round"));
        }
        try {
            ModbusPolling.on(host, source)
                    .round(src -> CompletableFuture.completedFuture(Boolean.TRUE))
                    .start();
            fail("缺 every 必须 fail-fast");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("every"));
        }
    }

    /**
     * ⑥b 构建契约（严格模式 fail-fast）：host / source 为 null 必须显式拒绝——
     * host 必填使生产轮询必然挂到设备生命周期（无双 API），null 不落静默无宿主形态。
     */
    @Test
    public void onNullHostOrSourceRejected() {
        ModbusSource source = newSource(2000);
        try {
            ModbusPolling.on(null, source);
            fail("on(null, source) 必须 fail-fast");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("host"));
        }
        try {
            ModbusPolling.on(host, null);
            fail("on(host, null) 必须 fail-fast");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("source"));
        }
    }

    /**
     * ⑧宿主绑定（18 号 §3.3 SDK 内绑）：start() 向 RemovalHost 注册恰好一条移除动作；
     * 动作执行（设备移除 sweep 语义）后轮询立即从周期网格摘除。cancel 纯标记确定性成立
     * （链 cancel 同步置停链标志），无窗口等待。
     */
    @Test
    public void removalHostBindingRegistersExactlyOneAction_andCancelsWhenRun() throws Exception {
        ModbusSource source = newSource(2000);
        CountDownLatch oneSuccess = new CountDownLatch(1);
        PollingHandle handle = ModbusPolling.on(host, source)
                .round(src -> {
                    oneSuccess.countDown(); // 首轮发起即观测点（onRound 观测面零消费已删）
                    return CompletableFuture.completedFuture(Boolean.TRUE);
                })
                .every(100, TimeUnit.MILLISECONDS)
                .start();

        assertTrue("前置：首轮必须成功（轮询在跑）", oneSuccess.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertTrue("轮询运行中", handle.isRunning());
        assertEquals("start() 必须向宿主注册恰好一条移除动作（SDK 内绑，宿主不做别的）",
                1, removalActions.size());

        removalActions.get(0).run(); // 模拟设备移除 sweep 执行移除动作
        assertFalse("移除动作执行后轮询必须停（从周期网格摘除）", handle.isRunning());
    }

    /**
     * ⑦组合（SDK 周期 × 同源 FIFO 单飞）：一轮 round 内两帧并发提交，同源待发队列单飞
     * drain 保证一次一帧；两轮 round 之间（完成点驱动）同源帧串行不并发——全程
     * master.send 在飞数恒 ≤ 1。
     */
    @Test
    public void periodicRoundsSerializeFramesOnSameSource_singleFlightFifo() throws Exception {
        ModbusMaster master = mock(ModbusMaster.class);
        ModbusSource source = newSource(2000);
        TestTools.setPrivateField(source, "modbusMaster", master);

        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        AtomicInteger sendCalls = new AtomicInteger();
        CountDownLatch firstFrameStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstFrame = new CountDownLatch(1);
        when(master.send(any(ModbusRequest.class))).thenAnswer(inv -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            if (sendCalls.incrementAndGet() == 1) {
                firstFrameStarted.countDown();
                releaseFirstFrame.await(); // 首帧按住总线
            }
            inFlight.decrementAndGet();
            return mock(ReadHoldingRegistersResponse.class);
        });

        CountDownLatch twoSuccessRounds = new CountDownLatch(2);
        PollingHandle handle = ModbusPolling.on(host, source)
                .round(src -> CompletableFuture.allOf(
                        src.readHoldingRegisters(0, 1),
                        src.readHoldingRegisters(2, 1))
                        .thenApply(v -> {
                            // 双帧串行完成才计一轮（onRound 观测面零消费已删，round 体即观测点）
                            twoSuccessRounds.countDown();
                            return Boolean.TRUE;
                        }))
                .every(100, TimeUnit.MILLISECONDS)
                .start();

        assertTrue("首帧应已进入 master.send", firstFrameStarted.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertEquals("同源 FIFO 单飞：首帧在飞期间，同轮第二帧不得进入 master.send",
                1, sendCalls.get());

        releaseFirstFrame.countDown();
        assertTrue("两轮成功（每轮双帧串行完成后才进下一轮）",
                twoSuccessRounds.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertEquals("两轮 round 间同源帧串行：全程在飞帧数不得 > 1（实际峰值 "
                + maxInFlight.get() + "）", 1, maxInFlight.get());
        handle.cancel();
    }
}
