package com.ecat.integration.ModbusIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.ecat.core.Task.LockBusySkippedException;

/**
 * 【E2/R3 终态修复，方案 c】modbus 轮询路径 acquire 非阻塞化（调度三原则「过期即弃」）
 * 的契约测试，与 serial {@code SerialPollingNonBlockingTest} 同型。
 *
 * <p>红测形态（修复前）：轮询任务体经 {@code executeWithLambda} 取锁，锁忙时
 * {@code ModbusSource.acquire()} 在 waitQueue 上 park 等待——共享连接/RS485 上的秒级
 * 阻塞事务互相排队即饱和震荡（Q-F 终验 WEDGE-RECOVERY ~30/min 不收敛）。
 *
 * <p>契约：
 * <ul>
 *   <li>①{@code tryAcquire}/{@code executePolling}：锁忙时本周期立即放弃（毫秒级返回，
 *       零 park、不进 waitQueue、不消费 signal），future 以
 *       {@link LockBusySkippedException} 异常完成；</li>
 *   <li>②放弃有记账：{@code getLockBusySkipCount()} 递增（禁静默）；</li>
 *   <li>③写命令路径（{@code executeWithLambda}）不回归：锁忙仍按有限等待 park。</li>
 * </ul>
 */
public class ModbusPollingNonBlockingTest {

    /** 轮询入口的非阻塞上界：远小于等锁 park 时长；留足 CI 慢机余量。 */
    private static final long NON_BLOCKING_BOUND_MS = 2_000;
    /** 写路径等待超时（构造时注入 500ms，缩短测试墙钟；park 语义不变）。 */
    private static final int WAIT_TIMEOUT_MS = 500;
    /** 写路径 park 下界：证明仍在等锁（≥ 等待超时的一半）。 */
    private static final long WRITE_WAIT_MIN_MS = 300;

    private ModbusSource newSource() {
        return new ModbusSource(new ModbusTcpInfo("127.0.0.1", 19999, 1), 1, WAIT_TIMEOUT_MS) {
            @Override
            public void closeModbus() {
                // 测试不建真实 master（与 ModbusGhostLockReapTest 同边界）
            }
        };
    }

    /** 契约①端口级：锁忙时 tryAcquire 立即返回 null，不污染等待队列，且有记账。 */
    @Test
    public void tryAcquireReturnsNullImmediately_whenLockHeld_andLeavesWaitQueueClean() {
        ModbusSource source = newSource();
        String held = source.acquire(1, TimeUnit.SECONDS);
        assertNotNull("前置：首次取锁应成功", held);

        long start = System.currentTimeMillis();
        String busy = source.tryAcquire();
        long elapsed = System.currentTimeMillis() - start;

        assertNull("锁忙时 tryAcquire 应立即放弃返回 null", busy);
        assertTrue("tryAcquire 必须非阻塞（耗时 " + elapsed + "ms）", elapsed < NON_BLOCKING_BOUND_MS);
        assertEquals("锁忙放弃应计数 1 次", 1L, source.getLockBusySkipCount());
        assertEquals("tryAcquire 不得入等待队列", 0, source.getWaitingCount());

        // 无 waiter 泄漏：释放后快速路径立即可得
        assertTrue(source.release(held));
        String next = source.acquire(200, TimeUnit.MILLISECONDS);
        assertNotNull("tryAcquire 不得污染 waitQueue（释放后应立即取得锁）", next);
        source.release(next);
    }

    /** 契约①策略级【红→绿主证】：锁忙时 executePolling 毫秒级以 LockBusySkippedException 完成。 */
    @Test
    public void executePollingSkipsImmediatelyWithLockBusySkipped_whenLockHeld() throws Exception {
        ModbusSource source = newSource();
        String held = source.acquire(1, TimeUnit.SECONDS);
        assertNotNull(held);

        long start = System.currentTimeMillis();
        CompletableFuture<Boolean> future = ModbusTransactionStrategy.executePolling(
                source, src -> CompletableFuture.completedFuture(true));
        try {
            future.get(NON_BLOCKING_BOUND_MS, TimeUnit.MILLISECONDS);
            throw new AssertionError("锁忙时 executePolling 应异常完成而非成功");
        } catch (ExecutionException e) {
            assertTrue("异常类型应为 LockBusySkippedException，实际: " + e.getCause(),
                    e.getCause() instanceof LockBusySkippedException);
        }
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("executePolling 必须毫秒级返回（耗时 " + elapsed + "ms），不得 park 等锁",
                elapsed < NON_BLOCKING_BOUND_MS);
        assertEquals(1L, source.getLockBusySkipCount());

        // 锁空闲时 executePolling 正常执行事务并释放
        assertTrue(source.release(held));
        CompletableFuture<Boolean> ok = ModbusTransactionStrategy.executePolling(
                source, src -> CompletableFuture.completedFuture(true));
        assertTrue(ok.get(5, TimeUnit.SECONDS));
        String again = source.acquire(200, TimeUnit.MILLISECONDS);
        assertNotNull("事务完成后锁应已释放", again);
        source.release(again);
    }

    /** 契约③写路径不回归：锁忙时 executeWithLambda 仍按有限等待 park 后异常完成。 */
    @Test
    public void executeWithLambdaKeepsBoundedWait_whenLockHeld() throws Exception {
        ModbusSource source = newSource();
        String held = source.acquire(1, TimeUnit.SECONDS);
        assertNotNull(held);

        long start = System.currentTimeMillis();
        CompletableFuture<Boolean> future = ModbusTransactionStrategy.executeWithLambda(
                source, src -> CompletableFuture.completedFuture(true));
        try {
            future.get(15, TimeUnit.SECONDS);
            throw new AssertionError("锁忙时 executeWithLambda 应等待超时后异常完成");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof IllegalStateException);
        }
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("写路径应保留有限等待（park 至少 " + WRITE_WAIT_MIN_MS + "ms，实际 " + elapsed + "ms）",
                elapsed >= WRITE_WAIT_MIN_MS);
        assertTrue(source.release(held));
    }

    /** 契约①并发形态：共享连接上多设备轮询互相不 park——持锁期间 N 个 tryAcquire 全部立即放弃。 */
    @Test
    public void concurrentPollersAllSkipImmediately_whenLockHeld() throws Exception {
        ModbusSource source = newSource();
        String held = source.acquire(1, TimeUnit.SECONDS);
        assertNotNull(held);

        int pollers = 4;
        CountDownLatch done = new CountDownLatch(pollers);
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean>[] results = new CompletableFuture[pollers];
        for (int i = 0; i < pollers; i++) {
            results[i] = ModbusTransactionStrategy.executePolling(
                    source, src -> CompletableFuture.completedFuture(true));
            results[i].whenComplete((r, e) -> done.countDown());
        }
        assertTrue("全部轮询 future 应立即完成（无 park）",
                done.await(NON_BLOCKING_BOUND_MS, TimeUnit.MILLISECONDS));
        for (CompletableFuture<Boolean> f : results) {
            assertTrue(f.isCompletedExceptionally());
        }
        assertEquals(pollers, source.getLockBusySkipCount());
        assertTrue(source.release(held));
    }
}
