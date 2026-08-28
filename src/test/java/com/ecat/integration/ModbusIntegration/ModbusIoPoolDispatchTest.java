package com.ecat.integration.ModbusIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ecat.core.Utils.TestTools;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.msg.ModbusRequest;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersRequest;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersResponse;
import com.serotonin.modbus4j.msg.WriteRegisterResponse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R3 期 4 契约（15 号设计 §6.4，D4）：modbus 阻塞 send（master.send 含写+等回音）迁到
 * 有界 IO 旁池 {@code ecat-modbus-io-N}，与调度引擎车道彻底分离——
 * <ul>
 *   <li>①worker 解放：send 阻塞期间发起它的引擎车道必须立即可服务后继任务（发起即返，
 *       响应窗的阻塞消耗由旁池线程承接——084500 风暴族「每事务钉死一个 worker」的根除形态）；</li>
 *   <li>②线程归属：send 只允许出现在 {@code ecat-modbus-io-*} 线程（读与写同规），
 *       永不出现在引擎 worker / 发起线程上（R6 jstack 断言的微观形态）；</li>
 *   <li>③同连接串行保持：同源两次 send 严格一次一帧（防 RS485/共享连接帧碰撞——
 *       modbus4j RtuMaster.sendImpl 无方法级同步，串行是本集成的契约而非库的恩赐）；</li>
 *   <li>④池饱和快速失败：N 线程全忙时新 send 立即以 RejectedExecutionException 异常完成
 *       （AbortPolicy；过期即弃——不排队不阻塞发起段）。</li>
 * </ul>
 * 确定性同步 = latch/线程名捕获，无 Thread.sleep。
 */
public class ModbusIoPoolDispatchTest {

    /** 调用方车道替身（同键串行语义=单线程执行器；W7 引擎车道退役后的等价 fixture）。 */
    private ExecutorService lane;
    private ModbusMaster master;

    @Before
    public void setUp() {
        master = mock(ModbusMaster.class);
        lane = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "modbus-lane-test");
            t.setDaemon(true);
            return t;
        });
    }

    @After
    public void tearDown() {
        // 复位默认池（本类容量实验隔离；后续类的 source 惰性建池——shutdown 已是终端态，测试基建用复位）
        ModbusIoPool.resetForTest();
        lane.shutdownNow();
    }

    private ModbusSource newSource() throws Exception {
        ModbusTcpInfo info = new ModbusTcpInfo("127.0.0.1", 1502, 1);
        ModbusSource source = new ModbusSource(info, 1, 2000, true, false);
        TestTools.setPrivateField(source, "modbusMaster", master);
        return source;
    }

    /**
     * ①【红→绿主证】send 阻塞期间调用方车道不被钉死：轮询发起段（生产=调用方车道上的周期任务体）
     * 提交读后立即返回，同车道后继任务必须立即可执行。
     * 红（改造前实证）：读事务体 supplyAsync 到本源 lane 视图——send 把车道线程钉死整个
     * 响应窗，后继任务 2s 超时（modbus 测试基线 2026-08-26；W7 后车道 fixture=单线程执行器）。
     */
    @Test
    public void blockingSendMustNotPinLaneWorker() throws Exception {
        ModbusSource source = newSource();
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        when(master.send(any(ReadHoldingRegistersRequest.class))).thenAnswer(inv -> {
            sendStarted.countDown();
            releaseSend.await(); // 假 IO：按住整个响应窗
            return mock(ReadHoldingRegistersResponse.class);
        });

        lane.submit(() -> source.readHoldingRegisters(0, 1)); // 发起段（发起即返，不 join）
        assertTrue("send 应已进入在飞（被闩按住）", sendStarted.await(5, TimeUnit.SECONDS));

        assertEquals("send 在飞期间同车道后继任务必须立即可执行（发起即返，worker 未被钉死）",
                "next", lane.submit(() -> "next").get(2, TimeUnit.SECONDS));
        releaseSend.countDown();
    }

    /**
     * ②读/写的 send 线程归属：必须运行在 ecat-modbus-io-* 旁池线程（读与写同规）。
     * 红（改造前实证）：读在兜底/lane 线程（modbus-source-*），写在发起线程直发。
     */
    @Test
    public void sendRunsOnlyOnIoPoolThreads_readAndWrite() throws Exception {
        ModbusSource source = newSource();
        AtomicReference<String> readThread = new AtomicReference<>();
        AtomicReference<String> writeThread = new AtomicReference<>();
        CountDownLatch readDone = new CountDownLatch(1);
        CountDownLatch writeDone = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(master.send(any(ModbusRequest.class))).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                readThread.set(Thread.currentThread().getName());
                readDone.countDown();
                return mock(ReadHoldingRegistersResponse.class);
            }
            writeThread.set(Thread.currentThread().getName());
            writeDone.countDown();
            return mock(WriteRegisterResponse.class);
        });

        source.readHoldingRegisters(0, 1);
        assertTrue(readDone.await(5, TimeUnit.SECONDS));
        assertTrue("读 send 线程必须是旁池线程，实际: " + readThread.get(),
                readThread.get().startsWith("ecat-modbus-io-"));

        source.writeRegister(100, 27);
        assertTrue(writeDone.await(5, TimeUnit.SECONDS));
        assertTrue("写 send 线程必须是旁池线程，实际: " + writeThread.get(),
                writeThread.get().startsWith("ecat-modbus-io-"));

        // 池线程 daemon（共享设施不阻 JVM 退出——替代旧本地兜底线程命名/非 daemon 契约）
        Thread poolThread = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals(readThread.get()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("旁池线程应存活: " + readThread.get()));
        assertTrue("旁池线程必须 daemon", poolThread.isDaemon());
    }

    /**
     * ③同连接串行保持（防帧碰撞回归护栏）：同源第二个 send 必须等第一个完成后才进入
     * master.send（mock 无 synchronized——串行只能来自 ModbusSource 自身的连接级 send 许可）。
     */
    @Test
    public void sameSourceSendsSerializeOneFrameInFlight() throws Exception {
        ModbusSource source = newSource();
        ReadHoldingRegistersResponse response = mock(ReadHoldingRegistersResponse.class);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();

        when(master.send(any(ReadHoldingRegistersRequest.class))).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                firstStarted.countDown();
                releaseFirst.await(); // 第一帧按住总线
                events.add("first-end");
            } else {
                events.add("second-start");
            }
            return response;
        });

        CompletableFuture<ReadHoldingRegistersResponse> first = source.readHoldingRegisters(0, 1);
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        CompletableFuture<ReadHoldingRegistersResponse> second = source.readHoldingRegisters(2, 1);

        // 第一帧在飞期间，第二帧不得进入 master.send（总线一次一帧）
        assertEquals("第二帧不得与第一帧并发进入 master.send", 1, calls.get());
        releaseFirst.countDown();
        assertEquals(response, first.get(5, TimeUnit.SECONDS));
        assertEquals(response, second.get(5, TimeUnit.SECONDS));
        assertEquals("同源两帧必须按 first→second 顺序执行",
                java.util.Arrays.asList("first-end", "second-start"), events);
    }

    /**
     * ④池饱和快速失败：容量 1 + 排队 4（4×N）的旁池——1 线程被按住 + 4 个排队 drain 任务
     * 占满后，新源的 send 立即以 RejectedExecutionException 异常完成（AbortPolicy——
     * 过期即弃，不阻塞发起段）；排队内的帧不失败（突发吸收），释放后按序完成。
     * source 必须在 initialize 之后构造（旁池引用构造期捕获）。
     */
    @Test
    public void poolSaturationFailsFastWithRejection() throws Exception {
        ModbusIoPool.initialize(1); // 1 线程 + 4 排队容量
        CountDownLatch releaseSend = new CountDownLatch(1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        AtomicInteger entered = new AtomicInteger();
        when(master.send(any(ReadHoldingRegistersRequest.class))).thenAnswer(inv -> {
            if (entered.incrementAndGet() == 1) {
                firstStarted.countDown();
                releaseSend.await(); // 唯一池线程被首源按住
            }
            return mock(ReadHoldingRegistersResponse.class);
        });

        // 首源：占住唯一线程；4 个后续源：drain 任务占满 4 个排队槽（帧未执行即排队）
        ModbusSource first = newSource();
        CompletableFuture<ReadHoldingRegistersResponse> firstSend = first.readHoldingRegisters(0, 1);
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        CompletableFuture<ReadHoldingRegistersResponse>[] queued = new CompletableFuture[4];
        for (int i = 0; i < queued.length; i++) {
            ModbusSource queuedSource = newSource();
            queued[i] = queuedSource.readHoldingRegisters(2, 1);
        }

        ModbusSource overflowSource = newSource();
        long startNanos = System.nanoTime();
        CompletableFuture<ReadHoldingRegistersResponse> rejected = overflowSource.readHoldingRegisters(4, 1);
        try {
            rejected.get(2, TimeUnit.SECONDS);
            fail("池与排队全满时新 send 必须异常完成");
        } catch (ExecutionException e) {
            assertTrue("拒绝原因必须是 RejectedExecutionException，实际: " + e.getCause(),
                    e.getCause() instanceof RejectedExecutionException);
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        assertTrue("池满拒绝必须立即（发起段零阻塞，实际 " + elapsedMs + "ms）", elapsedMs < 1_000L);
        for (CompletableFuture<ReadHoldingRegistersResponse> q : queued) {
            assertTrue("排队槽内的帧不得被拒绝失败（突发吸收）", !q.isCompletedExceptionally());
        }

        releaseSend.countDown();
        assertTrue("释放后首帧正常完成（拒绝不污染池状态）", firstSend.get(5, TimeUnit.SECONDS) != null);
        for (CompletableFuture<ReadHoldingRegistersResponse> q : queued) {
            assertTrue("释放后排队帧按序完成", q.get(5, TimeUnit.SECONDS) != null);
        }
    }

    /**
     * ⑤异连接并行：两源（=两条连接）的 send 在旁池上并行——各持各的连接级 send 许可，
     * 被 barrier 汇合互等证明真并发（被串行化则 barrier 超时 → 事务异常 → 红）。
     * 旧 lane 形态契约（异 source 车道并行）在旁池形态下的等价保持。
     */
    @Test
    public void distinctSourcesRunConcurrentlyOnIoPool() throws Exception {
        ModbusSource sourceA = newSource();
        ModbusSource sourceB = newSource();
        ReadHoldingRegistersResponse response = mock(ReadHoldingRegistersResponse.class);
        java.util.concurrent.CyclicBarrier bothRunning = new java.util.concurrent.CyclicBarrier(2);

        when(master.send(any(ModbusRequest.class))).thenAnswer(inv -> {
            bothRunning.await(5, TimeUnit.SECONDS);
            return response;
        });

        CompletableFuture<ReadHoldingRegistersResponse> fromA = sourceA.readHoldingRegisters(0, 1);
        CompletableFuture<ReadHoldingRegistersResponse> fromB = sourceB.readHoldingRegisters(0, 1);

        assertEquals(response, fromA.get(10, TimeUnit.SECONDS));
        assertEquals(response, fromB.get(10, TimeUnit.SECONDS));
    }

    /**
     * ⑥池生命周期与单源生死解耦：单源销毁（destroyResources）不得影响共享旁池——
     * 同池其他源照常收发（旧契约「单源销毁不得停掉共享引擎」在旁池形态下的等价保持，
     * bug-record-20260728-170000 死源防线的邻接语义）。旁池归集成 onRelease 统一关停。
     */
    @Test
    public void singleSourceDestroyDoesNotKillSharedPool() throws Exception {
        ModbusSource doomed = newSource();
        ModbusSource survivor = newSource();
        ReadHoldingRegistersResponse response = mock(ReadHoldingRegistersResponse.class);
        when(master.send(any(ModbusRequest.class))).thenReturn(response);
        when(master.isInitialized()).thenReturn(true);

        assertTrue(doomed.isModbusOpen());
        doomed.destroyResources();
        assertTrue("销毁后死源必须报 closed", !doomed.isModbusOpen());

        assertEquals("共享旁池必须不受单源销毁影响，幸存源照常收发",
                response, survivor.readHoldingRegisters(0, 1).get(5, TimeUnit.SECONDS));
    }
}
