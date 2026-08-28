package com.ecat.integration.ModbusIntegration;

import static org.junit.Assert.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * S4（19 号 v2）：事务计时委托迁移到域自持 {@code ModbusSdkTimers} 后，「零阻塞」定容
 * 论证（2 条 sched 线程）的保护契约——看门狗/硬超时触发的传输强拆
 * （{@code forceRecoverTransport}，含 destroy/init 可阻塞动作）不得占用域 sched 池线程，
 * 必须卸载到 {@link ModbusIoPool}（{@code ecat-modbus-io-N}）执行。
 *
 * <p>判定用线程名前缀（NamedThreadFactory 命名契约：{@code ecat-modbus-io-} /
 * {@code ecat-modbus-sched-}），事件观察用确定性闩，不用固定 sleep 猜测。
 * 迁移前红：强拆跑在 core TimeoutScheduler 计时线程（{@code core-timeout-local}），
 * 钉死 2 条 sched 池线程击穿定容论证。
 *
 * @author coffee
 */
public class ModbusWedgeRecoveryIoPoolOffloadTest {

    /** ModbusIoPool 线程名前缀（NamedThreadFactory("ecat-modbus-io") 命名契约）。 */
    private static final String IO_POOL_THREAD_PREFIX = "ecat-modbus-io-";

    /** 契约①：事务硬超时路径的传输强拆跑在 ModbusIoPool 线程，非计时（sched）线程。 */
    @Test(timeout = 20_000)
    public void hardTimeoutRecoveryRunsOnIoPool() throws Exception {
        CountDownLatch recoverLatch = new CountDownLatch(1);
        AtomicReference<String> recoverThread = new AtomicReference<>();
        ModbusSource source = new ModbusSource(
                new ModbusTcpInfo("127.0.0.1", 1502, 1, ModbusProtocol.TCP, 50),
                1, 1000, true, false) {
            @Override
            public void closeModbus() {
            }

            @Override
            public void forceRecoverTransport(String reason) {
                recoverThread.set(Thread.currentThread().getName());
                recoverLatch.countDown();
                // 不调 super：本测只验证执行线程归属，不触发真实传输动作
            }
        };

        // 永不完成的事务 future = 传输挂死形态（Q-1/A2）→ 硬超时（50ms×6=300ms）触发强拆
        ModbusTransactionStrategy.executeWithLambda(source, src -> new CompletableFuture<>());

        assertTrue("硬超时必须在有界时间内触发传输强拆", recoverLatch.await(5, TimeUnit.SECONDS));
        assertIoPoolThread(recoverThread.get());
    }

    /** 契约②：apply 阶段看门狗（F-40）触发的传输强拆同样卸载到 ModbusIoPool。 */
    @Test(timeout = 20_000)
    public void applyWatchdogRecoveryRunsOnIoPool() throws Exception {
        CountDownLatch wedged = new CountDownLatch(1);
        CountDownLatch neverRelease = new CountDownLatch(1); // 从不开放：apply 挂死模拟
        CountDownLatch recoverLatch = new CountDownLatch(1);
        AtomicReference<String> recoverThread = new AtomicReference<>();
        ModbusSource source = new ModbusSource(
                new ModbusTcpInfo("127.0.0.1", 1502, 1, ModbusProtocol.TCP, 50),
                1, 1000, true, false) {
            @Override
            public void closeModbus() {
            }

            @Override
            public void forceRecoverTransport(String reason) {
                recoverThread.set(Thread.currentThread().getName());
                recoverLatch.countDown();
                // 不调 super：本测只验证执行线程归属，不触发真实传输动作
            }
        };

        // 挂死事务必须在独立线程上跑（生产写路径 IO 体在命令/车道线程；主线程只做观察）
        Thread wedgedThread = new Thread(() -> ModbusTransactionStrategy.executeWithLambda(source, src -> {
            wedged.countDown();
            try {
                neverRelease.await(); // 模拟写直发 master.send 挂死（本测不解除，daemon 收尾）
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new CompletableFuture<>();
        }), "s4-wedged-apply");
        wedgedThread.setDaemon(true);
        wedgedThread.start();

        assertTrue("apply 应已进入执行", wedged.await(5, TimeUnit.SECONDS));
        assertTrue("apply 看门狗必须在有界时间内触发传输强拆", recoverLatch.await(5, TimeUnit.SECONDS));
        assertIoPoolThread(recoverThread.get());
    }

    private static void assertIoPoolThread(String threadName) {
        assertTrue("传输强拆必须跑在 ModbusIoPool 线程（" + IO_POOL_THREAD_PREFIX
                + "*，可阻塞动作不占 2 条 sched 定容线程），实际线程: " + threadName,
                threadName != null && threadName.startsWith(IO_POOL_THREAD_PREFIX));
    }
}
