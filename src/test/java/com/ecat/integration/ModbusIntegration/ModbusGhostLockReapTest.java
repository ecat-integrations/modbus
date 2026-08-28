package com.ecat.integration.ModbusIntegration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * 【RED：Q-1/Q-2 二轮幽灵锁，与 serial SerialSourcePortGhostLockReapTest 同型】
 * release 执行链整体丢失（计时任务入队被拒/持有线程被 master.send 无界等待吸收）时
 * currentKey 成永久幽灵锁：后续 acquire 永远超时（live 实证 Acquire timeout 持续报
 * 死 key），forceRecoverTransport 只强拆传输、不清逻辑锁，恢复循环不收敛。
 *
 * <p>契约：
 * <ul>
 *   <li>持锁时长超过收割阈值 → 下一次 acquire 入口按 release 同一状态机强制清零并放行，
 *       forceRecoverTransport 同点收割；</li>
 *   <li>持锁时长未超阈值（可能仍是合法长事务）→ 不得收割。</li>
 * </ul>
 */
public class ModbusGhostLockReapTest {

    private static final long REAP_THRESHOLD_MS = 100;
    private static final long CROSS_THRESHOLD_WAIT_MS = 300;
    private static final long ACQUIRE_TIMEOUT_MS = 500;

    private ModbusSource newSource() {
        ModbusSource source = new ModbusSource(new ModbusTcpInfo("127.0.0.1", 19999, 1)) {
            @Override
            public void closeModbus() {
            }
        };
        source.setGhostReapThresholdMsForTest(REAP_THRESHOLD_MS);
        return source;
    }

    /** 幽灵锁形态：持锁 key 的 release 永久缺失 → 阈值后下一次 acquire 收割放行。 */
    @Test
    public void acquireReapsGhostLock_whenHeldBeyondThreshold() throws Exception {
        ModbusSource source = newSource();
        String ghostKey = source.acquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertNotNull("首次 acquire 应成功取得锁", ghostKey);

        // 定时等待跨过收割阈值（latch 永不 countDown，await 到点返回 false 属预期，取墙钟推进语义）
        new CountDownLatch(1).await(CROSS_THRESHOLD_WAIT_MS, TimeUnit.MILLISECONDS);

        String revived = source.acquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertNotNull("幽灵锁应被收割，后续 acquire 应立即可得（修复前此处为 null = 红）", revived);

        assertFalse("幽灵 key 的迟到 release 应无效", source.release(ghostKey));
        assertTrue(source.release(revived));
    }

    /** 契约：未超阈值不得收割（合法长事务保护）。 */
    @Test
    public void ghostLockNotReaped_belowThreshold() throws Exception {
        ModbusSource source = newSource();
        String heldKey = source.acquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertNotNull(heldKey);

        String blocked = source.acquire(50, TimeUnit.MILLISECONDS);
        assertNull("阈值内持锁是合法持有，不得收割", blocked);

        assertTrue(source.release(heldKey));
        String next = source.acquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertNotNull(next);
        source.release(next);
    }
}
