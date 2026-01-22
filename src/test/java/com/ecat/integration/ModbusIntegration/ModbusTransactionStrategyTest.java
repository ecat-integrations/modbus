package com.ecat.integration.ModbusIntegration;

import org.junit.*;
import org.mockito.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ModbusTransactionStrategy 测试用例
 *
 * @author coffee
 */
public class ModbusTransactionStrategyTest {

    @Mock
    private ModbusSource modbusSource;

    private AutoCloseable mockitoCloseable;

    @Before
    public void setUp() throws Exception {
        mockitoCloseable = MockitoAnnotations.openMocks(this);
        modbusSource = mock(ModbusSource.class);
    }

    @After
    public void tearDown() throws Exception {
        mockitoCloseable.close();
    }

    /**
     * 正常执行 lambda 并释放锁
     */
    @Test
    public void testExecuteWithLambdaSuccess() throws Exception {
        String key = "lock-key";
        when(modbusSource.acquire()).thenReturn(key);
        CompletableFuture<Boolean> future = ModbusTransactionStrategy.executeWithLambda(
                modbusSource,
                src -> CompletableFuture.completedFuture(true));
        assertTrue(future.get());
        verify(modbusSource, times(1)).release(key);
    }

    /**
     * lambda 异常时释放锁
     */
    @Test
    public void testExecuteWithLambdaException() throws Exception {
        String key = "lock-key";
        when(modbusSource.acquire()).thenReturn(key);
        when(modbusSource.release(key)).thenReturn(true);

        CompletableFuture<Boolean> future = ModbusTransactionStrategy.executeWithLambda(
                modbusSource,
                src -> {
                    CompletableFuture<Boolean> f = new CompletableFuture<>();
                    f.completeExceptionally(new RuntimeException("test error"));
                    return f;
                });
        try {
            future.get();
            fail("Should throw ExecutionException");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof RuntimeException);
            assertEquals("test error", e.getCause().getMessage());
        }
        verify(modbusSource, times(1)).release(key);
    }

    /**
     * 无法获取锁时返回异常 future
     */
    @Test
    public void testExecuteWithLambdaLockFail() throws Exception {
        when(modbusSource.acquire()).thenReturn(null);
        when(modbusSource.getModbusInfo()).thenReturn(mock(ModbusTcpInfo.class));
        when(modbusSource.getMaxWaiters()).thenReturn(1);
        when(modbusSource.getWaitingCount()).thenReturn(0);

        CompletableFuture<Boolean> future = ModbusTransactionStrategy.executeWithLambda(
                modbusSource,
                src -> CompletableFuture.completedFuture(true));
        try {
            future.get();
            fail("Should throw ExecutionException");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof IllegalStateException);
        }
        verify(modbusSource, never()).release(any());
    }

    /**
     * 并发场景下锁互斥
     * 测试executeWithLambda在并发环境下的基本调用流程
     */
    @Test
    public void testExecuteWithLambdaConcurrency() throws Exception {
        // 使用真实的ModbusTcpInfo而不是Mock，避免协议类型错误
        ModbusTcpInfo tcpInfo = new ModbusTcpInfo("127.0.0.1", 502, 1);
        ModbusSource source = new ModbusSource(tcpInfo, 2, 1000); // 设置较小的超时时间

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);

        CompletableFuture<Boolean> f1 = new CompletableFuture<>();
        CompletableFuture<Boolean> f2 = new CompletableFuture<>();

        Runnable task1 = () -> {
            try {
                latch.await();
                ModbusTransactionStrategy.executeWithLambda(source, src -> {
                    try {
                        Thread.sleep(50); // 减少等待时间
                    } catch (InterruptedException ignored) {
                    }
                    f1.complete(true);
                    return CompletableFuture.completedFuture(true);
                });
            } catch (Exception e) {
                f1.completeExceptionally(e);
            }
        };

        Runnable task2 = () -> {
            try {
                latch.await();
                ModbusTransactionStrategy.executeWithLambda(source, src -> {
                    f2.complete(true);
                    return CompletableFuture.completedFuture(true);
                });
            } catch (Exception e) {
                f2.completeExceptionally(e);
            }
        };

        executor.submit(task1);
        executor.submit(task2);

        latch.countDown();
        // 等待两个任务完成
        f1.get();
        f2.get();

        executor.shutdown();
        source.closeModbus(); // 清理资源
    }
}
