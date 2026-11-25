package com.ecat.integration.ModbusIntegration.Sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Device.RemovalHost;
import com.ecat.core.Utils.Mdc.MdcContext;
import com.ecat.integration.ModbusIntegration.ModbusProtocol;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.ModbusTcpInfo;

/**
 * {@link ModbusPolling#startup} 启动序列契约（bug-record-20261124-214200 定谳的 SDK 侧修复）：
 * 「首轮读之前在源锁事务入口顺序做一次性初始化写」的一等入口——上电默认写与轮询链的
 * 半双工竞态由结构消除（startup 先行、周期链后起，同一条对话队列天然串行），设备不再
 * 靠 delay+重试赌运气。契约面：
 * <ul>
 *   <li>时序：startup 在首轮 round 之前完成；startup 成功前周期链不起（无轮拍）；</li>
 *   <li>重试：SDK 统一提供 attempts×retryDelay 重试（业务 FALSE 与异常同格折算可重试）；
 *       全部尝试失败 ⇒ 轮询不启动、句柄失败态、onStartupFailure 回调（严格模式不静默起轮）；</li>
 *   <li>生命周期：cancel（宿主移除 sweep 绑定）取消在飞 startup 与待发重试拍——主动停机
 *       非故障，不触发失败回调；</li>
 *   <li>存量零变化：无 startup 的用法照旧首发即发（既有 ModbusPollingChainTest 锁面）。</li>
 * </ul>
 * 同步纪律：fake 定时缝 + 手动到拍，零 sleep 零真实时钟（与 ModbusPollingChainTest 同构）。
 */
public class ModbusPollingStartupTest {

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
    private ModbusSource newSource() {
        return new ModbusSource(
                new ModbusTcpInfo("127.0.0.1", 19999, 1, ModbusProtocol.TCP, 2000),
                1, 500, true, false) {
            @Override
            public void closeModbus() {
            }
        };
    }

    // ==================== 时序契约：startup 先行，周期链后起 ====================

    @Test
    public void startupCompletesBeforeFirstRound_roundStartsOnlyAfterSuccess() {
        List<String> events = new CopyOnWriteArrayList<>();
        ModbusSource source = newSource();
        PollingHandle handle = ModbusPolling.on(host, source)
                .startup(src -> {
                    events.add("startup");
                    return CompletableFuture.completedFuture(Boolean.TRUE);
                })
                .round(src -> {
                    events.add("round");
                    return CompletableFuture.completedFuture(Boolean.TRUE);
                })
                .every(5, TimeUnit.SECONDS)
                .withNanoClock(nanoClock::get)
                .start();

        assertEquals("start 后仅应有一拍：startup 首发拍（周期链未起，首轮拍不存在）",
                1, timers.shots.size());
        assertEquals("startup 首发拍延迟 0（立即发起，无 delay 赌运气）", 0L, timers.shots.get(0).delayMillis);
        assertTrue("startup 成功前 round 不得执行", events.isEmpty());
        assertTrue("startup 在飞期间句柄处于存活态（生命周期已激活待入网）", handle.isRunning());
        assertFalse("未失败", handle.isStartupFailed());

        timers.fire(0); // startup 尝试：源锁快路径直达、事务体在拍线程内联完成 → 成功起周期链
        assertEquals("startup 恰好执行一次", 1, events.size());
        assertEquals("startup", events.get(0));
        assertEquals("startup 成功后周期链起：首轮拍入队", 2, timers.shots.size());
        assertEquals("入网首拍延迟=initialDelay（默认 0：startup 后立即首轮读，无整周期等待）",
                0L, timers.shots.get(1).delayMillis);
        assertEquals("round 仍未执行（等首拍到点）", 1, events.size());

        timers.fire(1); // 首轮
        assertEquals("round 在 startup 之后才执行（先初始化再周期读）", 2, events.size());
        assertEquals("round", events.get(1));
        handle.cancel();
    }

    // ==================== 重试契约：SDK 统一重试，耗尽即失败终态 ====================

    @Test
    public void businessFalseIsRetriedUpToAttempts_thenTerminalFailureNoPolling() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger rounds = new AtomicInteger();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        PollingHandle handle = ModbusPolling.on(host, newSource())
                .startup(src -> CompletableFuture.completedFuture(
                        attempts.incrementAndGet() < 2 ? Boolean.FALSE : Boolean.TRUE),
                        2, 500)
                .onStartupFailure(failures::add)
                .round(src -> {
                    rounds.incrementAndGet();
                    return CompletableFuture.completedFuture(Boolean.TRUE);
                })
                .every(5, TimeUnit.SECONDS)
                .withNanoClock(nanoClock::get)
                .start();

        timers.fire(0); // 尝试 1：业务 FALSE → 重试拍
        assertEquals("尝试 1 失败后重试拍入队（SDK 统一重试，非整链出局）", 2, timers.shots.size());
        assertEquals("重试拍延迟=声明的 retryDelay", 500L, timers.shots.get(1).delayMillis);

        timers.fire(1); // 尝试 2：成功 → 周期链起
        assertEquals("尝试 2 成功后周期链起（重试后照常入网）", 3, timers.shots.size());
        assertFalse("重试成功即非失败态", handle.isStartupFailed());

        timers.fire(2); // 首轮
        assertEquals("round 恰好执行一次", 1, rounds.get());
        assertEquals("重试计数：恰 2 次尝试", 2, attempts.get());
        assertTrue("失败回调不得误触发", failures.isEmpty());
        handle.cancel();
    }

    @Test
    public void exceptionAttemptsExhausted_failsTerminal_noRoundEver() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger rounds = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        PollingHandle handle = ModbusPolling.on(host, newSource())
                .startup(src -> {
                    attempts.incrementAndGet();
                    CompletableFuture<Boolean> failed = new CompletableFuture<>();
                    failed.completeExceptionally(new java.io.IOException("io-boom-" + attempts.get()));
                    return failed;
                })
                .onStartupFailure(failure::set)
                .round(src -> {
                    rounds.incrementAndGet();
                    return CompletableFuture.completedFuture(Boolean.TRUE);
                })
                .every(5, TimeUnit.SECONDS)
                .withNanoClock(nanoClock::get)
                .start();

        timers.fire(0); // 尝试 1：异常出局 → 重试
        timers.fire(1); // 尝试 2：异常出局 → 重试
        timers.fire(2); // 尝试 3：异常出局 → 耗尽终态

        assertEquals("3 次尝试全部执行（attempts 默认 3）", 3, attempts.get());
        assertTrue("全部尝试失败 ⇒ 句柄失败态", handle.isStartupFailed());
        assertFalse("失败态轮询不在网", handle.isRunning());
        assertEquals("轮询必须未启动：round 零执行（严格模式不静默起轮）", 0, rounds.get());
        assertEquals("无更多拍（周期链未起、无孤儿重试）", 3, timers.shots.size());
        assertNotNull("onStartupFailure 必须收到根因", failure.get());
        assertTrue("根因必须保留原始异常", failure.get() instanceof java.io.IOException);
        assertEquals("io-boom-3", failure.get().getMessage());
    }

    @Test
    public void oneArgOverloadDefaultsThreeAttemptsAndTwoSecondRetryDelay() {
        AtomicInteger attempts = new AtomicInteger();
        PollingHandle handle = ModbusPolling.on(host, newSource())
                .startup(src -> CompletableFuture.completedFuture(Boolean.FALSE)) // 恒失败：探默认档
                .round(src -> CompletableFuture.completedFuture(Boolean.TRUE))
                .every(5, TimeUnit.SECONDS)
                .withNanoClock(nanoClock::get)
                .start();

        timers.fire(0); // 尝试 1 失败 → 默认档重试拍
        assertEquals("默认重试拍延迟=2000ms（校准自 tianhong 既有消费面 3 次/2s）",
                2_000L, timers.shots.get(1).delayMillis);
        timers.fire(1); // 尝试 2 失败 → 第二拍
        assertEquals("第二拍同延迟", 2_000L, timers.shots.get(2).delayMillis);
        timers.fire(2); // 尝试 3 失败 → 耗尽（默认 attempts=3）
        assertTrue("默认 3 次后必须终态失败", handle.isStartupFailed());
        assertEquals("恰 3 次尝试（1 首发拍 + 2 重试拍）", 3, timers.shots.size());
    }

    // ==================== 生命周期契约：cancel 取消在飞 startup 与待发重试 ====================

    @Test
    public void cancelDuringInFlightStartup_abortsSilently_noFailureCallback() {
        CompletableFuture<Boolean> inFlight = new CompletableFuture<>();
        AtomicInteger rounds = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        PollingHandle handle = ModbusPolling.on(host, newSource())
                .startup(src -> inFlight)
                .onStartupFailure(failure::set)
                .round(src -> {
                    rounds.incrementAndGet();
                    return CompletableFuture.completedFuture(Boolean.TRUE);
                })
                .every(5, TimeUnit.SECONDS)
                .withNanoClock(nanoClock::get)
                .start();

        timers.fire(0); // startup 在飞（CF 由测试持有——真实场景=事务在口上执行中）
        assertEquals("在飞事务不得重排任何拍", 1, timers.shots.size());

        handle.cancel(); // 宿主移除 sweep / 设备 stop 形态
        assertFalse("cancel 后不在网", handle.isRunning());
        assertFalse("主动停机非故障：不得置失败态", handle.isStartupFailed());

        inFlight.completeExceptionally(new java.io.IOException("late-transport-error"));
        assertEquals("cancel 后在飞事务迟到失败：无孤儿重试拍", 1, timers.shots.size());
        assertEquals("迟到失败不得触发失败回调（链已亡，无人消费）", null, failure.get());
        assertEquals("round 永不执行", 0, rounds.get());
    }

    @Test
    public void cancelBetweenRetries_cancelsPendingRetryShot_andStopsSequence() {
        AtomicInteger attempts = new AtomicInteger();
        PollingHandle handle = ModbusPolling.on(host, newSource())
                .startup(src -> {
                    attempts.incrementAndGet();
                    return CompletableFuture.completedFuture(Boolean.FALSE);
                }, 3, 500)
                .round(src -> CompletableFuture.completedFuture(Boolean.TRUE))
                .every(5, TimeUnit.SECONDS)
                .withNanoClock(nanoClock::get)
                .start();

        timers.fire(0); // 尝试 1 失败 → 重试拍 pending
        assertTrue("重试拍应在飞待发", timers.shots.get(1).future instanceof FakeModbusTimers.StubFuture);

        handle.cancel();
        assertTrue("cancel 必须撤销待发重试拍（P3 遗留债：entry 移除后孤儿重试链）",
                ((FakeModbusTimers.StubFuture) timers.shots.get(1).future).cancelled);
        assertFalse(handle.isRunning());
        assertFalse("cancel 非失败", handle.isStartupFailed());
        assertEquals("cancel 前恰 1 次尝试，重试拍被撤后不再递增", 1, attempts.get());
    }

    @Test
    public void removalBindingStillRegistersExactlyOneAction_inStartupMode() {
        PollingHandle handle = ModbusPolling.on(host, newSource())
                .startup(src -> CompletableFuture.completedFuture(Boolean.TRUE))
                .round(src -> CompletableFuture.completedFuture(Boolean.TRUE))
                .every(5, TimeUnit.SECONDS)
                .withNanoClock(nanoClock::get)
                .start();
        assertEquals("startup 模式同样注册恰好一条移除动作", 1, removalActions.size());
        removalActions.get(0).run(); // 模拟移除 sweep
        assertFalse("sweep 后句柄停", handle.isRunning());
    }

    // ==================== MDC 契约：startup 与 round 同在宿主 scope 内起链 ====================

    @Test
    public void deviceHostInjectsDeviceMdcIntoStartupBody_andKickoffSnapshot() throws Exception {
        ConfigEntry entry = new ConfigEntry();
        entry.setCoordinate("com.ecat:integration-modbus");
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("name", "modbus设备-01");
        DeviceBase deviceHost = new MinimalDevice(entry, cfg);

        AtomicReference<String> seenId = new AtomicReference<>();
        AtomicReference<String> seenName = new AtomicReference<>();
        PollingHandle handle = ModbusPolling.on(deviceHost, newSource())
                .startup(src -> {
                    seenId.set(MDC.get(MdcContext.DEVICE_ID_KEY));
                    seenName.set(MDC.get(MdcContext.DEVICE_NAME_KEY));
                    return CompletableFuture.completedFuture(Boolean.TRUE);
                })
                .round(src -> CompletableFuture.completedFuture(Boolean.TRUE))
                .every(5, TimeUnit.SECONDS)
                .withNanoClock(nanoClock::get)
                .start();

        assertEquals("startup 首发拍提交快照含 device.id（scope 注入被捕获）",
                deviceHost.getId(), timers.shots.get(0).submitMdc.get(MdcContext.DEVICE_ID_KEY));

        timers.fire(0); // startup 体执行：恢复快照后进事务体
        assertEquals("startup 体内恢复 device.id（帧归属与 round 同源）",
                deviceHost.getId(), seenId.get());
        assertEquals("startup 体内恢复 device.name",
                "modbus设备-01", seenName.get());
        handle.cancel();
    }

    /** 最小设备桩：仅承载 id/name/coordinate，生命周期全 no-op。 */
    private static final class MinimalDevice extends DeviceBase {
        MinimalDevice(ConfigEntry gatewayEntry, Map<String, Object> config) {
            super(gatewayEntry, "modbus-sdk-startup-test-unique", config);
        }

        @Override
        public void init() {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void release() {
        }
    }

    // ==================== 构建契约（严格模式 fail-fast；存量零变化） ====================

    @Test
    public void startStillRequiresRound_withStartupDeclared() {
        try {
            ModbusPolling.on(host, newSource())
                    .startup(src -> CompletableFuture.completedFuture(Boolean.TRUE))
                    .every(5, TimeUnit.SECONDS)
                    .withNanoClock(nanoClock::get)
                    .start();
            fail("startup 不豁免 round 必填（先初始化再周期读，周期读仍必填）");
        } catch (IllegalStateException expected) { }
    }

    @Test
    public void startupDeclarationFailFast() {
        ModbusPolling polling = ModbusPolling.on(host, newSource());
        try {
            polling.startup(null);
            fail("startup(null) 必须 fail-fast");
        } catch (IllegalArgumentException expected) { }
        try {
            polling.startup(src -> CompletableFuture.completedFuture(Boolean.TRUE));
            polling.startup(src -> CompletableFuture.completedFuture(Boolean.TRUE));
            fail("startup 重复声明必须 fail-fast（一次性启动序列只能有一个定义）");
        } catch (IllegalStateException expected) { }
        try {
            ModbusPolling.on(host, newSource()).startup(
                    src -> CompletableFuture.completedFuture(Boolean.TRUE), 0, 500);
            fail("attempts=0 必须 fail-fast（至少首发一次）");
        } catch (IllegalArgumentException expected) { }
        try {
            ModbusPolling.on(host, newSource()).startup(
                    src -> CompletableFuture.completedFuture(Boolean.TRUE), 3, -1);
            fail("retryDelayMs<0 必须 fail-fast");
        } catch (IllegalArgumentException expected) { }
        try {
            ModbusPolling.on(host, newSource()).onStartupFailure(null);
            fail("onStartupFailure(null) 必须 fail-fast");
        } catch (IllegalArgumentException expected) { }
    }
}
