package com.ecat.integration.ModbusIntegration.Sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ecat.core.Device.RemovalHost;
import com.ecat.core.Task.NamedThreadFactory;
import com.ecat.core.Utils.Mdc.MdcContext;
import com.ecat.core.Utils.Mdc.TraceContext;

/**
 * ModbusSdkTimers（modbus SDK 自持定时器，29 号 v2 S1 modbus 域）契约测试（与 http 域
 * HttpSdkTimersTest 同构）：
 * <ul>
 *   <li>MDC 传播最小集：提交时捕获（coordinate）、到拍恢复、无 traceId 则生成——由 core
 *       PeriodicRunner.fireAfter 内置（TraceContext 同一实现）；</li>
 *   <li>默认池形态：daemon + 命名线程 {@code ecat-modbus-sched-N}（线程预算可观测面）；</li>
 *   <li>停机钩子（{@code onRelease} 收口）：撤销在飞单发；终端态——停机后新提交 REE
 *       （R-F 与 serial/tcp/http 三域统一，同 JVM 重初始化属生产不存在的边界）；</li>
 *   <li>测试缝：bind(null) 拒绝；forScheduledExecutor 适配真 STPE（联调用）。</li>
 * </ul>
 */
public class ModbusSdkTimersTest {

    @Before
    public void setUp() {
        // 其他测试类可能经 ModbusIntegration.onRelease 关过默认池；本类要验证默认池形态，
        // 先复位取全新池（resetForTest 仅测试基建，生产无调用方）
        ModbusSdkTimers.resetForTest();
    }

    @After
    public void tearDown() {
        ModbusSdkTimers.unbindForTest();
        ModbusSdkTimers.resetForTest(); // 复位（非 shutdown）：终端态断言留在用例内，后续测试类可再建池
        MdcContext.clearCoordinate();
        TraceContext.clearTraceId();
    }

    @Test
    public void fireAfterPropagatesSubmitterMdcAndEnsuresTraceId() {
        FakeModbusTimers timers = new FakeModbusTimers();
        try {
            TraceContext.clearTraceId();
            MdcContext.setCoordinate("com-ecat:integration-modbus");
            final AtomicReference<String> seenCoordinate = new AtomicReference<>();
            final AtomicReference<String> seenTraceId = new AtomicReference<>();
            ModbusSdkTimers.fireAfter(() -> {
                seenCoordinate.set(MdcContext.getCoordinate());
                seenTraceId.set(TraceContext.getTraceId());
            }, 5L);

            // 提交侧快照含坐标（提交时捕获语义）
            assertEquals("提交时捕获 coordinate（快照面）", "com-ecat:integration-modbus",
                    timers.lastShot().submitMdc.get(MdcContext.INTEGRATION_COORDINATE_KEY));

            // 到拍侧：清空当前线程 MDC 后触发，命令内看到恢复出的坐标 + 补生成的 traceId
            TraceContext.restore(null);
            MdcContext.clearCoordinate();
            timers.fire(0);
            assertEquals("到拍恢复提交时 coordinate", "com-ecat:integration-modbus",
                    seenCoordinate.get());
            assertNotNull("无 traceId 的提交在到拍时补生成（引擎 wrapRunnable 同语义）",
                    seenTraceId.get());
        } finally {
            timers.close();
        }
    }

    @Test
    public void defaultPoolIsDaemonNamedEcatModbusSched() throws Exception {
        // 不 bind：走生产默认池（懒创建）
        final CountDownLatch fired = new CountDownLatch(1);
        final AtomicReference<String> threadName = new AtomicReference<>();
        final AtomicReference<Boolean> daemon = new AtomicReference<>();
        ModbusSdkTimers.fireAfter(() -> {
            threadName.set(Thread.currentThread().getName());
            daemon.set(Thread.currentThread().isDaemon());
            fired.countDown();
        }, 0L);

        assertTrue("默认池 0ms 单发必须在观察窗内到拍", fired.await(5, TimeUnit.SECONDS));
        assertTrue("线程名须为 ecat-modbus-sched-N（实际 " + threadName.get() + "）",
                threadName.get().matches("ecat-modbus-sched-\\d+"));
        assertEquals("SDK 定时线程必须 daemon（不阻 JVM 退出）", Boolean.TRUE, daemon.get());
    }

    /**
     * 停机语义（R-F：终端态，对齐 serial/tcp/http 三域——同 JVM 重初始化属生产不存在
     * 的边界，remove→onRelease 后必是新 JVM）：shutdown 撤销当前池后，新提交必须
     * 显式 REE（不静默懒重建、不丢任务）；resetForTest 重开测试窗口后恢复建池。
     */
    @Test
    public void shutdownIsTerminal_nextSubmissionRejectedUntilTestReset() throws Exception {
        final CountDownLatch firstFired = new CountDownLatch(1);
        ModbusSdkTimers.fireAfter(firstFired::countDown, 0L);
        assertTrue("前置：默认池单发到拍", firstFired.await(5, TimeUnit.SECONDS));

        ModbusSdkTimers.shutdown();

        try {
            ModbusSdkTimers.fireAfter(() -> { }, 1L);
            fail("停机后新提交必须 RejectedExecutionException（终端态，不自动复活）");
        } catch (RejectedExecutionException expected) { }

        ModbusSdkTimers.resetForTest();
        final CountDownLatch reArmed = new CountDownLatch(1);
        ModbusSdkTimers.fireAfter(reArmed::countDown, 0L);
        assertTrue("resetForTest（测试基建）后必须恢复建池服务", reArmed.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void bindForTestRejectsNull() {
        try {
            ModbusSdkTimers.bindForTest(null);
            fail("bind(null) 必须 IllegalArgumentException（解除用 unbindForTest）");
        } catch (IllegalArgumentException expected) { }
    }

    // ==================== SDK delay 统一词汇（公共静态一次性延迟）====================

    // 单参 delay(long) 静态词汇零生产消费已删（剃刀）；可撤销形态有真实设备仓消费，保留如下。

    /** 可撤销变体：宿主 sweep 执行移除动作撤销待发拍，CF 不完成（消费链随宿主死亡）。 */
    @Test
    public void delayHostVariantCancelsPendingShotOnHostSweep() throws Exception {
        FakeModbusTimers timers = new FakeModbusTimers();
        try {
            List<Runnable> removals = new ArrayList<>();
            CompletableFuture<Void> delayed = ModbusSdkTimers.delay(removals::add, 500L);
            assertEquals("delay(host, ms) 必须经域定时器单发提交（500ms 拍）", 500L,
                    timers.lastShot().delayMillis);
            assertEquals("可撤销变体必须注册恰好 1 个移除动作", 1, removals.size());
            assertFalse("到拍前 delay CF 不得完成", delayed.isDone());

            removals.get(0).run(); // 宿主 sweep（设备 cancelManagedTasks LIFO 执行移除动作）

            assertTrue("宿主 sweep 后待发拍必须被撤销（纯标记 cancel）",
                    timers.lastShot().future.isCancelled());
            assertFalse("被撤销的延迟 CF 不完成（写路径 IO 副作用随 stop 不再发射）",
                    delayed.isDone());
        } finally {
            timers.close();
        }
    }

    /** 宿主已 sweep：注册移除动作显式上抛 REE，且上抛前先撤销刚提交的待发拍（不留悬挂拍）。 */
    @Test
    public void delayHostVariantRejectsSweptHostWithoutDanglingShot() {
        FakeModbusTimers timers = new FakeModbusTimers();
        try {
            RemovalHost sweptHost = action -> {
                throw new RejectedExecutionException("设备已停止，拒绝注册移除动作");
            };
            try {
                ModbusSdkTimers.delay(sweptHost, 100L);
                fail("宿主已 sweep 时必须显式上抛 RejectedExecutionException");
            } catch (RejectedExecutionException expected) {
                // 显式失败信号（严格模式，不静默吞）
            }
            assertEquals("上抛前必须已提交单发", 1, timers.shots.size());
            assertTrue("上抛路径必须先行撤销待发拍（不留悬挂拍）",
                    timers.shots.get(0).future.isCancelled());
        } finally {
            timers.close();
        }
    }

    /** 参数拒绝路径：host null/负延迟在提交前拒绝（缝零提交证明）。 */
    @Test
    public void delayRejectsNegativeMillisAndNullHost() {
        FakeModbusTimers timers = new FakeModbusTimers();
        try {
            try {
                ModbusSdkTimers.delay(null, 5L);
                fail("delay(null, ms) 必须 IllegalArgumentException");
            } catch (IllegalArgumentException expected) { }
            try {
                ModbusSdkTimers.delay(action -> { }, -1L);
                fail("delay(host, 负数) 必须 IllegalArgumentException");
            } catch (IllegalArgumentException expected) { }
            assertEquals("拒绝路径不得提交任何单发", 0, timers.shots.size());
        } finally {
            timers.close();
        }
    }

    /** 真实定时器形态冒烟：forScheduledExecutor 适配真 STPE（联调用测试自备池）。 */
    @Test
    public void forScheduledExecutorAdaptsRealStpe() throws Exception {
        ScheduledThreadPoolExecutor stpe = new ScheduledThreadPoolExecutor(1,
                new NamedThreadFactory("modbus-sdk-adhoc-test", true));
        try {
            ModbusSdkTimers.bindForTest(ModbusSdkTimers.forScheduledExecutor(stpe));
            final CountDownLatch fired = new CountDownLatch(1);
            ModbusSdkTimers.fireAfter(fired::countDown, 0L);
            assertTrue("经适配器提交的真实 STPE 单发必须到拍", fired.await(5, TimeUnit.SECONDS));
        } finally {
            ModbusSdkTimers.unbindForTest();
            stpe.shutdownNow();
        }
    }

    /** 定时器池尺寸/线程工厂形态守护：默认池核心线程数 = 类内论证的 POOL_SIZE（2）。 */
    @Test
    public void defaultPoolSizedTwoPerBudgetArgument() throws Exception {
        final CountDownLatch probe = new CountDownLatch(1);
        ModbusSdkTimers.fireAfter(probe::countDown, 0L);
        assertTrue(probe.await(5, TimeUnit.SECONDS));
        Object pool = currentPoolByReflection();
        assertTrue("默认池必须是 ScheduledThreadPoolExecutor（实际 " + pool + "）",
                pool instanceof ScheduledThreadPoolExecutor);
        assertEquals("池尺寸须等于类内线程预算论证值（改尺寸须同步改类注释论证）",
                2, ((ScheduledThreadPoolExecutor) pool).getCorePoolSize());
    }

    /** 读 ModbusSdkTimers 私有静态池字段（形态守护断言用，仅测试面）。 */
    private static Object currentPoolByReflection() throws Exception {
        Field field = ModbusSdkTimers.class.getDeclaredField("pool");
        field.setAccessible(true);
        return field.get(null);
    }
}
