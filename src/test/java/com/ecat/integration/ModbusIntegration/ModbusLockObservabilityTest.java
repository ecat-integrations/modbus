package com.ecat.integration.ModbusIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.junit.Test;
import org.slf4j.LoggerFactory;

import com.ecat.core.CommTrace.ResourceOwner;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * 锁观测面（LOCKRCA 建议A，对齐 serial 版 {@code SerialSourcePort} Acquire timeout 样板）：
 * 偶发 5-12s 单事务卡顿在自愈带内无任何错误日志，等待方超时/取锁失败时日志必须能直读
 * 持锁者身份（key/线程/owner 业务身份）+已持时长+剩余等待者数，否则现场（容器日志随容器
 * 销毁）不可回溯即失证（10-07 三事故实录）；慢持锁（>1s，卡顿带下缘）在 release 点必须
 * 留一行 WARN 痕迹——健康段事务持锁 ms 级，>1s 即卡顿证据，无竞争也打点。零行为改动：
 * 全部为日志/观测面，锁状态机与返回值语义不变。
 */
public class ModbusLockObservabilityTest {

    private static final String COORDINATE = "com.ecat:integration-mb-lock-obs";

    private static ResourceOwner deviceOwner(String entryId, String deviceId) {
        return ResourceOwner.device(COORDINATE, entryId, deviceId);
    }

    /** 跳过 openModbus 的裸源（同包可达 protected 构造；锁观测不依赖 master）。 */
    private static ModbusSource bareSource(int maxWaiters, int waitTimeoutMs) {
        return new ModbusSource(new ModbusSerialInfo("/dev/null", 9600, 8, 1, 0, 500, 1),
                maxWaiters, waitTimeoutMs, true, false);
    }

    private static ListAppender<ILoggingEvent> attach(Level level, Class<?> loggerClass) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(loggerClass.getName());
        logbackLogger.setLevel(level);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        return appender;
    }

    private static void detach(Class<?> loggerClass, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(loggerClass.getName())).detachAppender(appender);
    }

    private static String joined(ListAppender<ILoggingEvent> appender, Level level) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    /** 等待者已入队的确定性断言（事件已发生再推进，非固定 sleep 猜测）。 */
    private static void awaitWaitingCount(ModbusSource source, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (source.getWaitingCount() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals("等待者应已入队", expected, source.getWaitingCount());
    }

    /**
     * Acquire timeout 超时行必须带持锁者真相：完整等待预算内零 release 只能是持锁事务
     * 卡顿，该行必须能直读持锁 key/线程/已持时长/owner 业务身份/剩余等待者数。
     */
    @Test
    public void acquireTimeoutLogCarriesHolderIdentity() throws Exception {
        ModbusSource source = bareSource(3, 300);
        String holderKey = source.acquire(deviceOwner("entry-1", "dev-holder"));
        assertNotNull(holderKey);

        ListAppender<ILoggingEvent> appender = attach(Level.ERROR, ModbusSource.class);
        try {
            // 第二个等待者先入队并保持排队：超时者摘除自身后剩余等待者=1，日志必须可见
            CountDownLatch secondDone = new CountDownLatch(1);
            AtomicReference<String> secondKey = new AtomicReference<>("sentinel");
            Thread second = new Thread(() -> {
                secondKey.set(source.acquire(2000, TimeUnit.MILLISECONDS,
                        deviceOwner("entry-1", "dev-second")));
                secondDone.countDown();
            }, "mb-obs-second-waiter");
            second.start();
            awaitWaitingCount(source, 1);

            String timedOut = source.acquire(300, TimeUnit.MILLISECONDS);
            assertNull("持锁未释放，短预算者应超时返回 null", timedOut);

            String formatted = joined(appender, Level.ERROR);
            assertTrue("超时行应含 Acquire timeout 前缀，实际：\n" + formatted,
                    formatted.contains("Acquire timeout: "));
            assertTrue("超时行应直读持锁 key，实际：\n" + formatted,
                    formatted.contains("lock currently held by: " + holderKey));
            assertTrue("超时行应含持锁线程，实际：\n" + formatted,
                    formatted.contains("by thread main"));
            assertTrue("超时行应含已持时长（held <n>ms），实际：\n" + formatted,
                    Pattern.compile("held [0-9]+ms").matcher(formatted).find());
            assertTrue("超时行应含持锁 owner 业务身份，实际：\n" + formatted,
                    formatted.contains("owner=DEVICE|") && formatted.contains("dev-holder"));
            assertTrue("超时行应含剩余等待者数（次者仍在队），实际：\n" + formatted,
                    formatted.contains("waiters=1"));

            // 收尾：放行第二个等待者（队头交接），两 key 各自 release，不留跨测残锁
            source.release(holderKey);
            assertTrue(secondDone.await(5, TimeUnit.SECONDS));
            assertNotNull("预算内应等到队头交接", secondKey.get());
            assertTrue(source.release(secondKey.get()));
        } finally {
            detach(ModbusSource.class, appender);
        }
    }

    /**
     * 策略层取锁失败行（errorMessage 的出处）必须附持锁者快照：maxWaiters/currentWaiting
     * 计数无法区分「卡顿持有中」与「恰在超时后自愈释放」，快照字段直读其一。
     */
    @Test
    public void strategyLockFailureLogCarriesHolderSnapshot() throws Exception {
        ModbusSource source = bareSource(3, 300);
        String holderKey = source.acquire(deviceOwner("entry-1", "dev-strat-holder"));
        assertNotNull(holderKey);

        ListAppender<ILoggingEvent> appender = attach(Level.ERROR, ModbusTransactionStrategy.class);
        try {
            CompletableFuture<Boolean> result = ModbusTransactionStrategy.executeWithLambda(source,
                    src -> CompletableFuture.completedFuture(Boolean.TRUE));
            try {
                result.get(2, TimeUnit.SECONDS);
                fail("锁不可得应异常完成");
            } catch (ExecutionException e) {
                assertTrue("失败原因应为 Failed to acquire lock，实际：" + e.getCause(),
                        String.valueOf(e.getCause().getMessage()).contains("Failed to acquire lock"));
            }
            String formatted = joined(appender, Level.ERROR);
            assertTrue("策略层失败行应附持锁者快照（key 直读），实际：\n" + formatted,
                    formatted.contains("Failed to acquire lock")
                            && formatted.contains("lock currently held by: " + holderKey));
        } finally {
            detach(ModbusTransactionStrategy.class, appender);
            source.release(holderKey);
        }
    }

    /**
     * 慢持锁打点（阈值经测试注入口缩短，持锁时长即被测行为本身）：release 时持锁超阈值
     * 打一行 WARN（身份+时长），健康持锁（低于阈值）零噪音——打点只认卡顿不认正常事务。
     */
    @Test
    public void slowHoldLogsWarnAtReleaseAndFastHoldStaysSilent() throws Exception {
        ModbusSource source = bareSource(3, 500);
        source.setSlowHoldWarnThresholdMsForTest(100);

        ListAppender<ILoggingEvent> appender = attach(Level.WARN, ModbusSource.class);
        try {
            String slowKey = source.acquire(deviceOwner("entry-1", "dev-slow"));
            assertNotNull(slowKey);
            Thread.sleep(250); // 持锁 250ms 跨 100ms 阈值（2.5x 裕度；被测行为=持锁时长本身）
            source.release(slowKey);

            String warn = joined(appender, Level.WARN);
            assertTrue("慢持锁应打 WARN 痕迹，实际：\n" + warn,
                    warn.contains("MODBUS-SLOW-LOCK-HOLD"));
            assertTrue("WARN 应含持锁 key，实际：\n" + warn,
                    warn.contains("lock currently held by: " + slowKey));
            assertTrue("WARN 应含已持时长，实际：\n" + warn,
                    Pattern.compile("held [0-9]+ms").matcher(warn).find());
            assertTrue("WARN 应含 owner 业务身份，实际：\n" + warn,
                    warn.contains("dev-slow"));

            // 负向：低于阈值的正常持锁释放不打点（健康 ms 级事务零噪音）
            appender.list.clear();
            String fastKey = source.acquire(deviceOwner("entry-1", "dev-fast"));
            source.release(fastKey);
            assertEquals("健康持锁不得打慢持锁 WARN：\n" + joined(appender, Level.WARN),
                    "", joined(appender, Level.WARN));
        } finally {
            detach(ModbusSource.class, appender);
        }
    }
}
