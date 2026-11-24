package com.ecat.integration.ModbusIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;

import com.ecat.core.CommTrace.ResourceOwner;

import static org.mockito.Mockito.mock;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.msg.ModbusMessage;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersResponse;

/**
 * 连接执行者契约测试（P3 工单七契约，serial 模式在 modbus 连接域的等价重建）：
 * <ul>
 *   <li>①同连接一场对话：同刻至多一帧在飞（线槽互斥，取代旧 pendingSends 单飞）；</li>
 *   <li>②满箱拒绝：HELD 队列满（capacity=maxWaiters）acquire 回 null（旧 Max waiters
 *       exceeded 语义，普通与高优先级两形态）；</li>
 *   <li>③高挤普通：高优先级到达且满，挤最新排队普通者（被挤者 null=轮询弃轮，
 *       LockBusySkippedException 由 ModbusSource/策略层按 null 转换）；</li>
 *   <li>④不打断在飞：排队/授予绝不打断进行中的对话（在飞帧跑完自己的生命周期）；</li>
 *   <li>⑤HELD deadline：release 链丢失被 hold deadline 强制收场（幽灵锁收割接替）；</li>
 *   <li>⑥帧 deadline → 传输失败同形 null 完成 + 强拆 → 下一帧自愈（RECOVERING 等死亡确认，
 *       keepAlive 自愈语义）；</li>
 *   <li>⑦MDC 传播：提交点捕获、IO worker 恢复（旧 dispatchIo 配方）。</li>
 * </ul>
 * 直接构造执行者（同包），FrameRunner/RecoveryHook 注入替身——不依赖真实 master。
 * 旧 BusArbitration 双红测（waitQueue/currentKey 反射钉竞态）随锁机制退役，其守护的
 * 不变量（无双授予/无死容量）以执行者 seam 重建：授予单点化（快路径+队列授予同
 * admissionLock）令双授予结构性不存在；离队摘除与授予同点令死 key 结构性不存在。
 */
public class ModbusConnectionExecutorContractTest {

    private static final ResourceOwner OWNER_A = ResourceOwner.device("com.ecat:mb-exec-ct", "e1", "dev-a");
    private static final ResourceOwner OWNER_B = ResourceOwner.device("com.ecat:mb-exec-ct", "e1", "dev-b");

    private ModbusConnectionExecutor executor;

    /** runner 进入次数（线槽互斥断言面）。 */
    private AtomicInteger entered;
    /** runner 在飞栅栏：首帧按住，release 后放行。 */
    private CountDownLatch runnerEntered;
    private CountDownLatch releaseRunner;
    /** worker 侧捕获：线程名与 MDC（⑦断言面）。 */
    private final AtomicReference<String> runnerThread = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> runnerMdc = new AtomicReference<>();
    /** 强拆钩子记录（⑥断言面）。 */
    private final List<String> recoveryReasons = new CopyOnWriteArrayList<>();

    @Before
    public void setUp() {
        entered = new AtomicInteger();
        runnerEntered = new CountDownLatch(1);
        releaseRunner = new CountDownLatch(1);
        ModbusIoPool.resetForTest(); // 隔离其他测试类对域池的终端态污染
    }

    @After
    public void tearDown() {
        ModbusIoPool.resetForTest();
    }

    /** 默认执行者（帧 deadline 关闭——⑥用专门构造的短 deadline 实例）。 */
    private ModbusConnectionExecutor newExecutor() {
        return new ModbusConnectionExecutor("ct:1502", 3, 0L, 300_000L, this::fakeSend, this::fakeRecover);
    }

    /** 帧替身：首帧按住总线（栅栏），其余立即返回；捕获线程名与 MDC。 */
    private ModbusMessage fakeSend(ModbusConnectionExecutor.IoRequestFactory factory) throws ModbusTransportException {
        runnerThread.set(Thread.currentThread().getName());
        runnerMdc.set(MDC.getCopyOfContextMap());
        if (entered.incrementAndGet() == 1) {
            runnerEntered.countDown();
            try {
                releaseRunner.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("runner interrupted", e);
            }
        }
        return mock(ReadHoldingRegistersResponse.class);
    }

    private void fakeRecover(String reason) {
        recoveryReasons.add(reason);
    }

    /** 请求工厂替身（帧内容由 modbus4j 域管，契约测试不依赖）。 */
    private ModbusConnectionExecutor.IoRequestFactory req() {
        return () -> null;
    }

    // ==================== ①同连接一场对话 ====================

    @Test
    public void framesSerializeOneInFlightPerConnection() throws Exception {
        executor = newExecutor();
        CompletableFuture<ModbusMessage> f1 = executor.submitFrame(req(), "e1", null);
        assertTrue("首帧应已进入 runner", runnerEntered.await(5, TimeUnit.SECONDS));
        CompletableFuture<ModbusMessage> f2 = executor.submitFrame(req(), "e2", null);

        // 第一帧在飞期间第二帧不得进入线槽（同连接一场对话）
        assertEquals("第二帧不得与第一帧并发进入 runner", 1, entered.get());
        releaseRunner.countDown();
        assertNotNull("首帧正常完成", f1.get(5, TimeUnit.SECONDS));
        assertNotNull("第二帧随后完成", f2.get(5, TimeUnit.SECONDS));
        assertEquals("全程 runner 只被依次进入两次", 2, entered.get());
        awaitWireIdle("在飞帧清零（线槽回到 IDLE；FrameDone 回投与 CF 完成有 µs 竞态）");
    }

    // ==================== ②满箱拒绝 ====================

    @Test
    public void fullQueueRejectsAcquireWithNull() throws Exception {
        executor = newExecutor(); // capacity=3
        String holder = executor.acquireAsync(OWNER_A, 50_000).get(5, TimeUnit.SECONDS);
        assertNotNull("前置：持有者取得连接", holder);

        // 3 个普通等待者占满队列（waitMs 拉长到 50s：本测内 null 完成只可能来自被挤出，
        // 排除等待超时完成的同形信号）；第 4 个普通：满拒 null（旧 Max waiters exceeded 语义）
        CompletableFuture<String>[] normals = newArray(3);
        for (int i = 0; i < 3; i++) {
            normals[i] = executor.acquireAsync(OWNER_B, 50_000);
            assertNotNull(normals[i]);
        }
        assertEquals("三个等待者应全部入队", 3, executor.getWaitingCount());
        assertNull("队列满：普通请求满拒回 null", executor.acquireAsync(OWNER_B, 50_000));
        assertEquals("满拒不占容量（计数不漂移）", 3, executor.getWaitingCount());

        // 高优先级形态：3 个高优先级（阻塞 acquire）并发到达，各自挤出一条普通者后占满
        // 队列；第 4 个高优先级立即满拒。被挤集合={全部 3 条普通}确定，逐条对应关系与
        // 入队序=线程调度序（不作断言）——断言只锁「集合被挤干净 + 全高满拒」契约
        AtomicReference<String>[] highKeys = newAtomicArray(3);
        CountDownLatch allEvicted = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            normals[i].whenComplete((key, ex) -> {
                assertNull("普通等待者被挤出以 null 完成（跳过语义）", key);
                allEvicted.countDown();
            });
            Thread t = new Thread(() -> {
                highKeys[idx].set(executor.acquire(null, 30_000));
            }, "ct-high-waiter-" + i);
            t.start();
        }
        assertTrue("三条普通等待者应全部被挤出", allEvicted.await(10, TimeUnit.SECONDS));
        assertEquals("队列应全为高优先级等待者", 3, executor.getWaitingCount());
        long rejectStart = System.nanoTime();
        assertNull("队列全高：高优先级满拒立即回 null（不等待）", executor.acquire(null, 30_000));
        assertTrue("满拒应立即（发起段零阻塞）",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - rejectStart) < 1_000);

        // 收尾：放行持有者 → 三条高优先级按实际入队序逐个授予 → 逐个等授予事件再放行
        // （授予链是串行的：n+1 号授予依赖 n 号 release，故每步只等「出现一个新的已授予
        // key」——不能对 3 计数闩一次等全，那会自锁死）
        assertTrue("持有者 release 应成功", executor.release(holder));
        boolean[] released = new boolean[3];
        for (int done = 0; done < 3; done++) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!hasUnreleasedKey(highKeys, released)) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("放行后高优先级等待者应授予 #" + done
                            + ", snapshot=" + executor.lockHolderSnapshot()
                            + ", waiting=" + executor.getWaitingCount());
                }
                Thread.sleep(5);
            }
            for (int i = 0; i < 3; i++) {
                if (!released[i] && highKeys[i].get() != null) {
                    executor.release(highKeys[i].get());
                    released[i] = true;
                    break;
                }
            }
        }
    }

    private static boolean hasUnreleasedKey(AtomicReference<String>[] keys, boolean[] released) {
        for (int i = 0; i < keys.length; i++) {
            if (!released[i] && keys[i].get() != null) {
                return true;
            }
        }
        return false;
    }

    // ==================== ③高挤普通 ====================

    @Test
    public void highArrivalEvictsNewestNormalWaiter() throws Exception {
        executor = newExecutor(); // capacity=3
        String holder = executor.acquireAsync(OWNER_A, 5_000).get(5, TimeUnit.SECONDS);
        assertNotNull(holder);

        // 3 个普通等待者占满
        CompletableFuture<String>[] normals = newArray(3);
        for (int i = 0; i < 3; i++) {
            normals[i] = executor.acquireAsync(OWNER_B, 5_000);
        }
        assertEquals(3, executor.getWaitingCount());

        // 高优先级到达且满：挤掉最新排队的普通者（其 CF 以 null 完成=弃轮语义），自己入队
        AtomicReference<String> highKey = new AtomicReference<>();
        CountDownLatch highGranted = new CountDownLatch(1);
        Thread highThread = new Thread(() -> {
            highKey.set(executor.acquire(null, 10_000));
            highGranted.countDown();
        }, "ct-high-arriver");
        highThread.start();

        assertNull("被挤普通者以 null 完成（跳过语义）", normals[2].get(5, TimeUnit.SECONDS));
        assertEquals("容量守恒：挤出+入队后仍 3 排队（2 普通+1 高）", 3, executor.getWaitingCount());
        assertFalse("高优先级在持有者释放前不得被授予", highGranted.await(300, TimeUnit.MILLISECONDS));

        // 释放持有者：高优先级按队列序（高先于普通）授予——此刻普通者仍未授予即序列证明
        executor.release(holder);
        assertTrue("高优先级应被授予", highGranted.await(5, TimeUnit.SECONDS));
        assertNotNull(highKey.get());
        assertFalse("高优先级先于普通等待者授予（优先级序列）", normals[0].isDone());

        // 收尾：释放高优先级 → 普通等待者按 FIFO 授予 → 各自 release（授予即持有，
        // 不释放则后续等待者永无轮次——acquireAsync 的 key 消费方负责 release）
        executor.release(highKey.get());
        String normalKey0 = normals[0].get(5, TimeUnit.SECONDS);
        assertNotNull("普通等待者随后授予", normalKey0);
        executor.release(normalKey0);
        String normalKey1 = normals[1].get(5, TimeUnit.SECONDS);
        assertNotNull(normalKey1);
        executor.release(normalKey1);
    }

    // ==================== ④不打断在飞 ====================

    @Test
    public void inFlightFrameNotInterruptedByConcurrentArrivals() throws Exception {
        executor = newExecutor();
        CompletableFuture<ModbusMessage> inFlight = executor.submitFrame(req(), "in-flight", null);
        assertTrue("首帧应已进入 runner", runnerEntered.await(5, TimeUnit.SECONDS));

        // 在飞期间高优先级授予 + 新帧入队：都不得打断在飞对话
        String concurrent = executor.acquireAsync(OWNER_A, 1_000).get(5, TimeUnit.SECONDS);
        assertNotNull("授予与线槽正交（modbus 语义），高优先级照常授予", concurrent);
        executor.submitFrame(req(), "queued", null);
        assertEquals("在飞帧不被打断（runner 仍为首帧）", 1, entered.get());

        releaseRunner.countDown();
        assertNotNull("在飞帧跑完自己的生命周期（正常完成）", inFlight.get(5, TimeUnit.SECONDS));
        assertEquals("无强拆被触发", 0, recoveryReasons.size());
    }

    // ==================== ⑤HELD deadline ====================

    @Test
    public void heldDeadlineForceEndsLostHolder() throws Exception {
        executor = newExecutor();
        executor.setHoldDeadlineMsForTest(100);
        String ghost = executor.acquire(null, 5_000);
        assertNotNull("前置：持有者取得连接", ghost);

        // 持有者永不 release；hold deadline（100ms）后执行者强制收场
        Thread.sleep(300);
        String revived = executor.acquireAsync(OWNER_B, 5_000).get(5, TimeUnit.SECONDS);
        assertNotNull("超时强制收场后连接应恢复授予", revived);
        assertFalse("旧 key 的迟到 release 无效", executor.release(ghost));
        assertTrue(executor.release(revived));
    }

    // ==================== ⑥帧 deadline → 强拆 → 自愈 ====================

    @Test
    public void frameDeadlineRecoversThenNextFrameHeals() throws Exception {
        CountDownLatch hangRelease = new CountDownLatch(1);
        executor = new ModbusConnectionExecutor("ct:1502", 3, 100L, 300_000L,
                factory -> {
                    runnerThread.set(Thread.currentThread().getName());
                    if (entered.incrementAndGet() == 1) {
                        // 首帧挂死形态：阻塞 send 永不返回（deadline 是唯一救援）
                        try {
                            hangRelease.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("hung send interrupted", e);
                        }
                        throw new ModbusTransportException("transport force-closed");
                    }
                    return mock(ReadHoldingRegistersResponse.class); // 强拆后 keepAlive 自愈的下一帧
                },
                this::fakeRecover);

        CompletableFuture<ModbusMessage> hung = executor.submitFrame(req(), "hung", null);
        assertNull("挂死帧应由 deadline 按传输失败同形完成 null（设备侧判空失败链必达）",
                hung.get(5, TimeUnit.SECONDS));
        // deadline 强拆（卸载恢复钩子）+ 等待死亡确认：RECOVERING 期间不开下一帧。
        // 挂死 send 得救（强拆使阻塞 IO 异常返回）→ FrameDone → RECOVERING 解除
        hangRelease.countDown();
        awaitRecoveryReasons(1, "deadline 应触发传输强拆钩子");
        assertEquals("executor-frame-hard-timeout", recoveryReasons.get(0));

        CompletableFuture<ModbusMessage> healed = executor.submitFrame(req(), "healed", null);
        assertNotNull("强拆后下一帧自愈（keepAlive 语义）", healed.get(5, TimeUnit.SECONDS));
        awaitWireIdle("在飞帧清零（强拆收场闭环）");
    }

    // ==================== ⑥b 帧 deadline 完成语义=传输失败同形 ====================

    /**
     * 帧 deadline 到点后调用方可见的 frame CF 完成 <b>null</b>，不是异常完成——与
     * runFrame 传输异常路径（catch ModbusTransportException → log.error + complete(null)）
     * 逐字同语义。deadline 的职责是执行者自愈执法（强拆/恢复），不改变 SDK 既有
     * 「传输失败→null」契约：设备仓判空→标故障冻结值的失败处理链对挂死帧仍必达
     * （迟到但触发，与旧无 deadline 世界同观测）。
     */
    @Test
    public void frameDeadlineCompletesNullLikeTransportFailure() throws Exception {
        CountDownLatch hangRelease = new CountDownLatch(1);
        executor = new ModbusConnectionExecutor("ct:1502", 3, 100L, 300_000L,
                factory -> {
                    if (entered.incrementAndGet() == 1) {
                        // 首帧挂死形态：阻塞 send 永不返回（deadline 是唯一救援）
                        try {
                            hangRelease.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("hung send interrupted", e);
                        }
                    }
                    return mock(ReadHoldingRegistersResponse.class);
                },
                this::fakeRecover);

        CompletableFuture<ModbusMessage> hung = executor.submitFrame(req(), "hung", null);
        try {
            assertNull("deadline 到点应按传输失败同形完成 null（不是异常完成）",
                    hung.get(5, TimeUnit.SECONDS));
            assertFalse("完成方式必须是正常 null 完成而非异常完成", hung.isCompletedExceptionally());
        } finally {
            hangRelease.countDown(); // 救出挂死 send，测试收场不留阻塞 worker
        }
        awaitRecoveryReasons(1, "deadline 应触发传输强拆钩子（自愈目的不变）");
        assertEquals("executor-frame-hard-timeout", recoveryReasons.get(0));
    }

    // ==================== ⑦MDC 传播 ====================

    @Test
    public void mdcCapturedAtSubmitRestoredOnWorker() throws Exception {
        executor = newExecutor();
        releaseRunner.countDown(); // runner 不按住，直接断言捕获面
        Map<String, String> submitted;
        MDC.put("coordinate", "com.ecat:mb-exec-ct#e1");
        try {
            submitted = MDC.getCopyOfContextMap();
            CompletableFuture<ModbusMessage> f = executor.submitFrame(req(), "mdc", submitted);
            assertNotNull(f.get(5, TimeUnit.SECONDS));
        } finally {
            MDC.remove("coordinate");
        }
        assertEquals("提交点 MDC 应在 IO worker 上恢复", submitted, runnerMdc.get());
        assertTrue("阻塞 send 应在 IO 旁池线程执行", runnerThread.get().startsWith("ecat-modbus-io"));
    }

    // ==================== HELD hold deadline 参数语义（裁定①：300s 等时平移） ====================

    /**
     * HELD hold deadline 默认值=幽灵收割阈值 300s（裁定①：×6 会引入新紧约束杀合法长
     * 持有者，300s 是旧安全网的等时平移）。强制收场「行为」由
     * {@link #heldDeadlineForceEndsLostHolder} 经测试缝缩短阈值覆盖；本用例锁「默认参数
     * 值 + ModbusSource 接线」不被实现时静默改动。
     */
    @Test
    public void holdDeadlineDefaultsToGhostReapThreshold() throws Exception {
        assertEquals("阈值常量语义保持（旧幽灵收割 300s 等时平移）",
                300_000L, ModbusSource.DEFAULT_GHOST_REAP_THRESHOLD_MS);
        // ModbusSource 构造把 300s 接线进执行者（反射读私有字段，防接线漂移）
        ModbusSource source = new ModbusSource(
                new com.ecat.integration.ModbusIntegration.ModbusTcpInfo("127.0.0.1", 1502, 1), 3, 2000, true, false);
        java.lang.reflect.Field execField = ModbusSource.class.getDeclaredField("connectionExecutor");
        execField.setAccessible(true);
        ModbusConnectionExecutor wired = (ModbusConnectionExecutor) execField.get(source);
        java.lang.reflect.Field holdField = ModbusConnectionExecutor.class.getDeclaredField("holdDeadlineMs");
        holdField.setAccessible(true);
        assertEquals("执行者 hold deadline 默认=幽灵收割阈值", 300_000L, holdField.getLong(wired));
    }

    // ==================== 4 并发读全受理（裁定③：准入与邮箱容量口径分立） ====================

    /**
     * 系统内至多 4 的口径钉死（裁定③）：有界准入 capacity=maxWaiters=3 只管 HELD 排队
     * （3 等待者+1 持有=4，{@link #fullQueueRejectsAcquireWithNull} 已锁满拒面）；帧侧
     * 无界——事务内并行段（saimosen allOf-4）4 帧并发提交全部受理、在线槽上依次完成，
     * 且不占用 HELD 准入容量（帧在飞/排队期间等待计数仍为 0，acquire 照常按 3 封顶
     * 入队）。防实现时把准入容量与帧邮箱容量混成一个数。
     */
    @Test
    public void fourConcurrentFramesAllAccepted_admissionBoundedOnlyForHeld() throws Exception {
        executor = newExecutor();
        releaseRunner.countDown(); // 帧即时完成，不按住线槽
        String holder = executor.acquireAsync(OWNER_A, 50_000).get(5, TimeUnit.SECONDS);
        assertNotNull(holder);

        // saimosen allOf-4 形态：事务内 4 帧并发提交——全部受理、无一满拒
        java.util.List<CompletableFuture<ModbusMessage>> frames = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            frames.add(executor.submitFrame(req(), "allOf-" + i, null));
        }
        assertEquals("帧不占 HELD 准入容量：等待计数仍为 0", 0, executor.getWaitingCount());
        for (int i = 0; i < 4; i++) {
            assertNotNull("并发帧必须全部完成（帧队列无界，满拒只属于 HELD 准入）",
                    frames.get(i).get(5, TimeUnit.SECONDS));
        }
        assertEquals("4 帧全部真实执行", 4, entered.get());

        // 准入容量与帧邮箱分立：4 帧期间 HELD 排队照常按 capacity=3 受理
        CompletableFuture<String> w1 = executor.acquireAsync(OWNER_B, 50_000);
        CompletableFuture<String> w2 = executor.acquireAsync(OWNER_B, 50_000);
        CompletableFuture<String> w3 = executor.acquireAsync(OWNER_B, 50_000);
        assertNull("HELD 准入仍 3 封顶：第 4 个等待者满拒", executor.acquireAsync(OWNER_B, 50_000));
        assertEquals("帧在飞不影响 HELD 等待计数", 3, executor.getWaitingCount());

        // 收尾：授予链串行（n+1 号授予依赖 n 号 release），逐个等授予再放行不留残槽
        executor.release(holder);
        String k1 = w1.get(5, TimeUnit.SECONDS);
        assertNotNull(k1);
        executor.release(k1);
        String k2 = w2.get(5, TimeUnit.SECONDS);
        assertNotNull(k2);
        executor.release(k2);
        String k3 = w3.get(5, TimeUnit.SECONDS);
        assertNotNull(k3);
        executor.release(k3);
    }

    // ==================== release 契约边界 ====================

    @Test
    public void releaseWrongKeyReturnsFalseAndKeepsHolder() {
        executor = newExecutor();
        String key = executor.acquire(null, 5_000);
        assertNotNull(key);
        assertFalse("无效 key release 拒绝", executor.release("c-never"));
        assertTrue("持有者快照仍为在持形态",
                executor.lockHolderSnapshot().contains("lock currently held by: " + key));
        assertTrue(executor.release(key));
        assertEquals("release 后快照回 free", "lock currently free", executor.lockHolderSnapshot());
    }

    // ==================== 小工具 ====================

    /** 线槽空闲事件等待（FrameDone 回投在 IO worker 上，晚于 CF 完成 µs 级——事件已发生再断言）。 */
    private void awaitWireIdle(String message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (executor.frameInFlightSeq() != -1L) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(message + ": frameInFlight=" + executor.frameInFlightSeq());
            }
            Thread.sleep(5);
        }
    }

    private static String convDump(Object conv) throws Exception {
        java.lang.reflect.Field seq = conv.getClass().getDeclaredField("seq");
        seq.setAccessible(true);
        java.lang.reflect.Field st = conv.getClass().getDeclaredField("state");
        st.setAccessible(true);
        java.lang.reflect.Field gr = conv.getClass().getDeclaredField("granted");
        gr.setAccessible(true);
        java.util.concurrent.CompletableFuture<?> g = (java.util.concurrent.CompletableFuture<?>) gr.get(conv);
        return "seq=" + seq.get(conv) + " state=" + st.get(conv) + " grantedDone=" + g.isDone()
                + " grantedNull=" + (g.isDone() && g.join() == null) + " @" + Integer.toHexString(conv.hashCode());
    }

    /** 等待者入队事件等待（deadline 轮询验证事件已发生，超时即失败）。 */
    /** 强拆钩子触发事件等待（恢复卸载在 IO 池线程异步执行，立即读数是竞态）。 */
    private void awaitRecoveryReasons(int expected, String message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (recoveryReasons.size() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(message + ": expected>=" + expected
                        + ", actual=" + recoveryReasons.size());
            }
            Thread.sleep(5);
        }
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<String>[] newArray(int size) {
        return new CompletableFuture[size];
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<String>[] newAtomicArray(int size) {
        AtomicReference<String>[] array = new AtomicReference[size];
        for (int i = 0; i < size; i++) {
            array[i] = new AtomicReference<>();
        }
        return array;
    }
}
