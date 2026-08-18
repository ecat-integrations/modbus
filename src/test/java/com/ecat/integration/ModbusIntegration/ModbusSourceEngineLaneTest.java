package com.ecat.integration.ModbusIntegration;

import com.ecat.core.Task.TaskManager;
import com.ecat.core.Utils.TestTools;
import com.ecat.core.Utils.Mdc.MdcContext;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.msg.ModbusRequest;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersRequest;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Modbus source 事务车道 → 共享调度引擎车道契约测试（IO 收敛 P2，57 专池线程 → 0）。
 *
 * <p>被测契约（ModbusSourceLanes）：
 * <ul>
 *   <li>同 source 的事务在引擎车道上严格串行（旧 per-source 单线程池语义 1:1 平移，防帧碰撞）。</li>
 *   <li>异 source 车道并行。</li>
 *   <li>死源判定 = 显式 destroyed 标志：destroyResources 后 isModbusOpen 必为 false，
 *       即便引擎车道视图 isShutdown 恒 false（借壳判定失效，bug-record-20260728-170000 防线平移）；
 *       且销毁不得停掉共享引擎（视图无独立生命周期，shutdown 抛 UOE）。</li>
 *   <li>bind seam：无 core 上下文可注入引擎实例确定性验证（B3 SerialTimeoutScheduler 同款）。</li>
 *   <li>死锁红线（调研 A.4.2）：会话（设备/MDC 车道）阻塞 join 自己提交的事务（source 车道）
 *       不得自等自死锁——事务只进 source 车道，会话永不与事务同道。</li>
 * </ul>
 *
 * <p>同步纪律：全程 latch/barrier 确定性同步，无 Thread.sleep；任务体内的 latch 等待是
 * 「假 IO」被测对象本身，不是测试同步。
 */
public class ModbusSourceEngineLaneTest {

    private TaskManager taskManager;
    private ModbusMaster master;

    @Before
    public void setUp() {
        taskManager = new TaskManager();
        ModbusSourceLanes.bind(taskManager);
        master = mock(ModbusMaster.class);
    }

    @After
    public void tearDown() {
        ModbusSourceLanes.unbind();
        taskManager.shutdownAll();
        MDC.clear();
    }

    /**
     * 构造绑定引擎车道下的 source：skipOpen=true（不碰网络）+ mock master 注入。
     */
    private ModbusSource newSource(String laneName) throws Exception {
        ModbusTcpInfo info = new ModbusTcpInfo("127.0.0.1", 502, 1);
        ModbusSource source = new ModbusSource(info, 1, 2000, true, false, laneName);
        TestTools.setPrivateField(source, "modbusMaster", master);
        return source;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue("latch 未在期限内放开", latch.await(5, TimeUnit.SECONDS));
    }

    /**
     * ① 同 source 两事务在引擎车道上严格串行：第二个事务只能在第一个完成后启动。
     * 反证窗口：第一个事务按住车道期间，异 lane 探针照常执行（有空闲 worker），
     * 而第二个事务必须仍未启动——若被并行执行则会立刻观察到「第一个未完成」。
     */
    @Test
    public void sameSourceTransactionsSerializeOnItsEngineLane() throws Exception {
        ModbusSource source = newSource("127.0.0.1:1502");
        ReadHoldingRegistersResponse response = mock(ReadHoldingRegistersResponse.class);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch firstDone = new CountDownLatch(1);
        AtomicBoolean secondSawFirstDone = new AtomicBoolean(false);
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();

        when(master.send(any(ReadHoldingRegistersRequest.class))).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                firstStarted.countDown();
                releaseFirst.await();          // 假 IO：按住本 source 车道
                events.add("first-end");
                firstDone.countDown();
            } else {
                events.add("second-start");
                secondSawFirstDone.set(firstDone.getCount() == 0L);
            }
            return response;
        });

        CompletableFuture<ReadHoldingRegistersResponse> first = source.readHoldingRegisters(0, 1);
        await(firstStarted);                   // 第一个事务已占住车道，才开始排第二个
        CompletableFuture<ReadHoldingRegistersResponse> second = source.readHoldingRegisters(2, 1);

        // 异 lane 探针：另一 worker 空闲可用，而同 source 的第二个事务仍被按住（串行的确定性反证）
        taskManager.executorFor("modbus-source:probe-other-lane").submit(() -> { }).get(5, TimeUnit.SECONDS);
        assertEquals("有空闲 worker 服务了异 lane 探针，同 source 的第二个事务不得先于第一个启动",
                1L, firstDone.getCount());
        assertTrue("第二个事务不得已启动（串行）", events.isEmpty());

        releaseFirst.countDown();
        assertEquals(response, first.get(5, TimeUnit.SECONDS));
        assertEquals(response, second.get(5, TimeUnit.SECONDS));
        assertEquals("同 source 两事务必须按 first→second 顺序执行",
                java.util.Arrays.asList("first-end", "second-start"), events);
        assertTrue("第二个事务启动时第一个必须已完成（同 source 串行）", secondSawFirstDone.get());
    }

    /**
     * ② 异 source 车道并行：两事务各等对方在场才放行（真 IO 形态的 barrier 汇合），
     * 被串行化则 barrier 超时 → 事务异常完成 → 本测试红。
     */
    @Test
    public void distinctSourceLanesRunConcurrently() throws Exception {
        ModbusSource sourceA = newSource("10.0.0.1:502");
        ModbusSource sourceB = newSource("10.0.0.2:502");
        ReadHoldingRegistersResponse response = mock(ReadHoldingRegistersResponse.class);
        CyclicBarrier bothRunning = new CyclicBarrier(2);

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
     * ③ 死源 destroyed 标志生效且不杀共享引擎：destroyResources 后 isModbusOpen 必为 false
     * （引擎视图 isShutdown 恒 false，旧 isShutdown 借壳判定在引擎车道下恒报 open → 死源复用）；
     * 同时共享引擎车道照常接受新任务（视图不销毁，在途任务自然排空）。
     */
    @Test
    public void destroyedFlagMarksDeadSourceWithoutKillingSharedEngine() throws Exception {
        ModbusSource source = newSource("10.0.0.3:502");
        when(master.isInitialized()).thenReturn(true);
        assertTrue("销毁前应 open", source.isModbusOpen());

        ExecutorService lane = taskManager.executorFor("modbus-source:10.0.0.3:502");
        assertSame("source 持有的必须是绑定引擎的同 key 缓存车道视图", lane, source.getLaneExecutor());

        source.destroyResources();   // 引擎车道视图下不得调 shutdown（会抛 UOE）
        assertFalse("destroyResources 后 isModbusOpen 必须为 false（destroyed 标志，master flag 仍 stale）",
                source.isModbusOpen());

        assertFalse("单源销毁不得停掉共享引擎（车道视图 isShutdown 恒随引擎）", lane.isShutdown());
        assertEquals("同车道销毁后必须照常接受新任务（视图不销毁、随任务自然空）",
                "after-destroy", lane.submit(() -> "after-destroy").get(5, TimeUnit.SECONDS));
    }

    /**
     * ④ bind seam 无 core 可测：绑定引擎时 source 车道 = 该引擎同 key 视图（幂等缓存实例）；
     * unbind 后回落本地兜底单线程（modbus-source-{conn} 命名，随源 shutdown 释放）。
     */
    @Test
    public void boundEngineResolvesLaneViewAndUnbindFallsBackToLocal() throws Exception {
        ModbusSource engineLaneSource = newSource("10.0.0.4:502");
        assertSame("绑定的引擎必须供出同 key 的缓存车道视图",
                taskManager.executorFor("modbus-source:10.0.0.4:502"), engineLaneSource.getLaneExecutor());

        ModbusSourceLanes.unbind();
        ModbusSource localFallbackSource = newSource("10.0.0.4:502");
        ExecutorService fallback = localFallbackSource.getLaneExecutor();
        assertNotSame("unbind 后不得再解析到引擎视图", taskManager.executorFor("modbus-source:10.0.0.4:502"), fallback);
        final String[] threadName = new String[1];
        fallback.submit(() -> threadName[0] = Thread.currentThread().getName()).get(5, TimeUnit.SECONDS);
        assertTrue("本地兜底保留具名线程（无 core 单测可观测性），实际：" + threadName[0],
                threadName[0].startsWith("modbus-source-10.0.0.4:502-"));

        when(master.isInitialized()).thenReturn(true);
        localFallbackSource.destroyResources();   // 本地兜底归源所有，随源 shutdown
        assertTrue("本地兜底 executor 销毁时必须 shutdown 释放线程", fallback.isShutdown());
        assertFalse("destroyed 标志对本地兜底路径同样生效", localFallbackSource.isModbusOpen());
    }

    /**
     * ⑤ 死锁红线（调研 A.4.2）：会话在设备车道（MDC 推导）上阻塞 join 自己提交的事务
     * （source 车道）必须完成。若事务被挪进会话同车道（自等自），join 超时 → 红。
     * 生产 readAndUpdate 会话虽是 continuation 风格不阻塞 join，本测试是车道分层的
     * 极限压力形态：会话车道与事务车道必须永不重合。
     */
    @Test
    public void sessionLaneBlockingJoinOnTransactionDoesNotSelfDeadlock() throws Exception {
        ModbusSource source = newSource("10.0.0.5:502");
        ReadHoldingRegistersResponse response = mock(ReadHoldingRegistersResponse.class);
        when(master.send(any(ModbusRequest.class))).thenReturn(response);

        MDC.put(MdcContext.INTEGRATION_COORDINATE_KEY, "com.ecat:integration-modbus-lane-test");
        CountDownLatch sessionDone = new CountDownLatch(1);
        AtomicReference<Throwable> sessionError = new AtomicReference<>();
        AtomicBoolean transactionValueSeen = new AtomicBoolean(false);
        taskManager.getMdcScheduledExecutorService().execute(() -> {
            try {
                transactionValueSeen.set(source.readHoldingRegisters(0, 1).get(10, TimeUnit.SECONDS) == response);
            } catch (Throwable t) {
                sessionError.set(t);
            } finally {
                sessionDone.countDown();
            }
        });

        assertTrue("会话车道阻塞 join source 车道事务不得自等自死锁", sessionDone.await(15, TimeUnit.SECONDS));
        assertNull("会话内 join 事务必须成功完成", sessionError.get());
        assertTrue(transactionValueSeen.get());
    }

    /**
     * 回归护栏：delegateMode（DeviceSpecificModbusSource 委托共享源）在引擎车道下
     * 依旧不创建自己的 executor（事务全走 delegate 的车道）。
     */
    @Test
    public void delegateModeStillCreatesNoExecutorUnderBoundEngine() throws Exception {
        ModbusSource delegate = new ModbusSource(
                new ModbusTcpInfo("127.0.0.1", 502, 2), 1, 2000, true, true, "ignored");
        assertNull("delegateMode 不得创建事务车道", delegate.getLaneExecutor());
    }
}
