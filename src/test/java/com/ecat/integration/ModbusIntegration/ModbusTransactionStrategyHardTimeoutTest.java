package com.ecat.integration.ModbusIntegration;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * Q-1/A2（P0 验收打回）modbus 锁挂死泄漏的 TDD 单测。
 *
 * <p>问题形态（P0-evidence：jstack 6/6 ecat-sched-worker 阻塞在 ModbusSource.acquire:445；
 * sim 全程存活的 modbus TCP 127.0.0.1:1504 锁同样 {@code Acquire timeout ... currentWaitingCount:0}）：
 * 事务体 {@code CompletableFuture.supplyAsync(() -> master.send(...), lane)} 在车道线程上同步执行
 * modbus4j send——底层 socket/串口写挂死时 send 永不返回（master.setTimeout 只约束等待响应路径），
 * future 永不 complete → {@code ModbusTransactionStrategy} 的 release 绑定在该 future 的
 * whenComplete 上永不执行 → 源锁（currentKey）成幽灵锁，持锁者已挂但无等待者登记
 * （currentWaitingCount:0），设备永久停更且不自愈。
 *
 * <p>修复契约（与 serial B5 同型补强，Q-1）：
 * <ol>
 *   <li>事务级硬超时：future 必然 complete（成功或 TimeoutException）→ release 必执行；</li>
 *   <li>硬超时 = 底层传输挂死强证据 → 触发 {@code ModbusSource.forceRecoverTransport}
 *       （从旁强拆底层传输原语：TCP socket / RTU 串口），阻塞的 send 立即异常返回、
 *       车道线程得救，keepAlive/RTU 下一事务按既有重连路径自愈；</li>
 *   <li>正常完成的事务不得误触发强拆。</li>
 * </ol>
 *
 * @author coffee
 */
public class ModbusTransactionStrategyHardTimeoutTest {

    /** 测试用请求超时；硬超时上限 = requestTimeoutMs × BOUNDED_READ_WAIT_FACTOR */
    private static final int TEST_REQUEST_TIMEOUT_MS = 100;

    /**
     * 【RED：Q-1/A2 幽灵锁】send future 永不 complete（= 车道线程钉死在挂死的 master.send 上）时，
     * release 必须仍被调用。修复前：ModbusTransactionStrategy 无事务级硬超时，release 永不执行
     * （verify 超时失败 = 复现 A2 的 ModbusSource.acquire 全员超时 wedge）。
     */
    @Test
    public void releaseFires_whenSendFutureNeverCompletes() throws Exception {
        ModbusSource source = mock(ModbusSource.class);
        when(source.acquire()).thenReturn("key-wedged");
        when(source.getRequestTimeoutMs()).thenReturn(TEST_REQUEST_TIMEOUT_MS);

        CompletableFuture<Boolean> result = ModbusTransactionStrategy.executeWithLambda(
                source, src -> new CompletableFuture<>());

        long hardTimeoutMs = ModbusTransactionStrategy.boundedReadWaitMs(source);
        verify(source, timeout(hardTimeoutMs + 2000).times(1)).release("key-wedged");

        try {
            result.get(hardTimeoutMs + 2000, TimeUnit.MILLISECONDS);
            fail("期望返回 future 因事务硬超时异常完成");
        } catch (ExecutionException ee) {
            assertTrue("cause 应为 TimeoutException，实际: " + ee.getCause(),
                    ee.getCause() instanceof java.util.concurrent.TimeoutException);
        }
    }

    /** 契约：事务硬超时（传输挂死强证据）必须触发传输强拆自愈。 */
    @Test
    public void transportRecoveryTriggered_onTransactionHardTimeout() throws Exception {
        ModbusSource source = mock(ModbusSource.class);
        when(source.acquire()).thenReturn("key-timeout");
        when(source.getRequestTimeoutMs()).thenReturn(TEST_REQUEST_TIMEOUT_MS);

        ModbusTransactionStrategy.executeWithLambda(
                source, src -> new CompletableFuture<>());

        long hardTimeoutMs = ModbusTransactionStrategy.boundedReadWaitMs(source);
        verify(source, timeout(hardTimeoutMs + 2000).times(1)).forceRecoverTransport(anyString());
    }

    /** 契约：事务正常完成时 release 正常触发，且不得误触发传输强拆。 */
    @Test
    public void noTransportRecovery_whenTransactionCompletesNormally() throws Exception {
        ModbusSource source = mock(ModbusSource.class);
        when(source.acquire()).thenReturn("key-normal");
        when(source.getRequestTimeoutMs()).thenReturn(TEST_REQUEST_TIMEOUT_MS);

        CompletableFuture<Boolean> result = ModbusTransactionStrategy.executeWithLambda(
                source, src -> CompletableFuture.completedFuture(true));

        assertTrue(result.get(2, TimeUnit.SECONDS));
        verify(source, timeout(1000).times(1)).release("key-normal");
        verify(source, never()).forceRecoverTransport(anyString());
    }
}
