package com.ecat.integration.ModbusIntegration;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * F-40：TCP 源 apply 阶段挂死（写路径直发）的有界恢复契约。
 *
 * <p>场景：写系列（writeXxxWithSlaveId）事务体在调用线程直发 master.send（M8 单飞队列
 * 自锁修复的合法形态）。TCP 传输半开时 send 永不返回 → executeHeld 阻塞在
 * lambda.apply 内部 → 既有事务硬超时（apply 返回后才武装）永不生效 → release /
 * forceRecoverTransport 均不执行 → 源锁被钉死、后续轮询 tryAcquire 全放弃、拥塞窗内
 * acquire 超时雪崩（ASM G11-5 根因）。
 *
 * <p>契约：apply 阶段挂死超过 boundedReadWaitMs 必须有界恢复——传输强拆被调
 * （forceRecoverTransport）+ 锁释放（后续 acquire 可得）。观察用确定性事件闩，
 * 不用固定 sleep 猜测。
 *
 * @author coffee
 */
public class ModbusApplyWedgeRecoveryTest {

    /**
     * 红：apply 挂死时必须在有界时间内触发传输强拆 + 释放源锁。
     * timeout=50ms → 有界上限 300ms，事件闩等 5s（远超上限，只验证有界性）。
     */
    @Test(timeout = 20_000)
    public void wedgedApplyIsBoundedRecovered() throws Exception {
        CountDownLatch wedged = new CountDownLatch(1);
        CountDownLatch neverRelease = new CountDownLatch(1); // 从不开放：apply 挂死模拟
        CountDownLatch recoverLatch = new CountDownLatch(1);
        AtomicInteger recoverCount = new AtomicInteger();
        AtomicReference<CompletableFuture<Boolean>> wedgedFutureRef = new AtomicReference<>();
        ModbusSource source = new ModbusSource(
                new ModbusTcpInfo("127.0.0.1", 1502, 1, ModbusProtocol.TCP, 50),
                1, 1000, true, false) {
            @Override
            public void closeModbus() {
            }

            @Override
            public void forceRecoverTransport(String reason) {
                recoverCount.incrementAndGet();
                recoverLatch.countDown();
                // 模拟真实语义：强拆关闭 socket → 阻塞在 send 上的线程异常返回（apply 得以返回）
                neverRelease.countDown();
                super.forceRecoverTransport(reason);
            }
        };

        // 挂死事务必须在独立线程上跑：生产写路径的 IO 体跑在命令/车道线程上，测试主线程
        // 扮演「后续事务」观察锁与恢复（若在主线程跑 apply，挂死被同步化、观察不到钉死）。
        CountDownLatch futureReturned = new CountDownLatch(1);
        Thread wedgedThread = new Thread(() -> {
            wedgedFutureRef.set(ModbusTransactionStrategy.executeWithLambda(source, src -> {
                wedged.countDown();
                try {
                    neverRelease.await(); // 模拟 master.send 挂死（强拆后闩开放 = send 异常返回）
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new CompletableFuture<>();
            }));
            futureReturned.countDown();
        }, "f40-wedged-apply");
        wedgedThread.setDaemon(true);
        wedgedThread.start();

        assertTrue("apply 应已进入执行", wedged.await(5, TimeUnit.SECONDS));
        // 前置：挂死 apply 钉住源锁（后续事务 acquire 必失败——拥塞窗表现）。
        // 100ms < 看门狗 300ms：验证发生在恢复之前的钉死窗口内。
        assertTrue("挂死期间锁应被钉死", source.acquire(100, TimeUnit.MILLISECONDS) == null);

        // 红：无 apply 阶段恢复时此闩 5s 超时失败（既有硬超时只在 apply 返回后武装，永等不到）
        assertTrue("挂死 apply 必须在有界时间内触发传输强拆", recoverLatch.await(5, TimeUnit.SECONDS));

        // 锁已被恢复路径释放：后续事务可正常取锁（恢复收敛，不残留幽灵锁）
        String key = source.acquire(2, TimeUnit.SECONDS);
        assertNotNull("恢复后 acquire 必须成功（锁已释放）", key);
        source.release(key);

        // future 层面：强拆后挂死线程得救返回，其 future 已被 watchdog 异常完成（TimeoutException 根因）
        assertTrue("强拆后挂死线程应得救返回", futureReturned.await(2, TimeUnit.SECONDS));
        CompletableFuture<Boolean> wedgedFuture = wedgedFutureRef.get();
        assertNotNull("挂死事务 future 应已返回", wedgedFuture);
        assertTrue("挂死事务 future 应已完成", wedgedFuture.isDone());
        try {
            wedgedFuture.getNow(null);
            throw new AssertionError("挂死事务 future 应异常完成，实际正常完成");
        } catch (java.util.concurrent.CompletionException expected) {
            Throwable root = expected.getCause() != null ? expected.getCause() : expected;
            assertTrue("挂死 apply 应以 TimeoutException 完成，实际: " + root,
                    root instanceof TimeoutException);
        }
        assertTrue("传输强拆至少一次", recoverCount.get() >= 1);
    }

    /**
     * 回归：apply 正常返回（写路径 completedFuture 形态）不受恢复机制影响，
     * 结果透传、锁正常释放、不误触发强拆。
     */
    @Test(timeout = 20_000)
    public void normalApplyUnaffected() throws Exception {
        AtomicInteger recoverCount = new AtomicInteger();
        ModbusSource source = new ModbusSource(
                new ModbusTcpInfo("127.0.0.1", 1502, 1, ModbusProtocol.TCP, 50),
                1, 1000, true, false) {
            @Override
            public void closeModbus() {
            }

            @Override
            public void forceRecoverTransport(String reason) {
                recoverCount.incrementAndGet();
                super.forceRecoverTransport(reason);
            }
        };

        CompletableFuture<Boolean> result = ModbusTransactionStrategy.executeWithLambda(source,
                src -> CompletableFuture.completedFuture(true));
        assertTrue("正常事务结果应透传", result.get(5, TimeUnit.SECONDS));
        assertTrue("正常事务不得触发传输强拆", recoverCount.get() == 0);
        String key = source.acquire(2, TimeUnit.SECONDS);
        assertNotNull("正常事务后锁应已释放", key);
        source.release(key);
    }
}
