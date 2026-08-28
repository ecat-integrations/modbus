package com.ecat.integration.ModbusIntegration;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import org.junit.Test;
import org.mockito.InOrder;

/**
 * 【RED：Q-1/Q-2 二轮】契约：事务硬超时路径 release 必须先于 forceRecoverTransport
 * 执行——recovery 含 destroy/init 等可能阻塞的动作（modbus4j sendImpl 与 destroy/init
 * 同步在同一 master 监视锁上，阻塞风险实证），若 recovery 在前且中途阻塞，release 被
 * 无限期推迟，currentKey 直接升级为幽灵锁（live 实证持锁 45min+）。
 */
public class ModbusRecoveryOrderTest {

    private static final int TEST_REQUEST_TIMEOUT_MS = 100;
    private static final long VERIFY_TIMEOUT_MS = 3000L;

    @Test
    public void releaseBeforeForceRecover_onTransactionHardTimeout() {
        ModbusSource source = mock(ModbusSource.class);
        when(source.acquire()).thenReturn("key-order");
        when(source.getRequestTimeoutMs()).thenReturn(TEST_REQUEST_TIMEOUT_MS);

        ModbusTransactionStrategy.executeWithLambda(
                source, src -> new CompletableFuture<>());

        verify(source, timeout(VERIFY_TIMEOUT_MS)).release("key-order");
        verify(source, timeout(VERIFY_TIMEOUT_MS)).forceRecoverTransport(anyString());
        InOrder inOrder = inOrder(source);
        inOrder.verify(source).release("key-order");
        inOrder.verify(source).forceRecoverTransport(anyString());
    }
}
