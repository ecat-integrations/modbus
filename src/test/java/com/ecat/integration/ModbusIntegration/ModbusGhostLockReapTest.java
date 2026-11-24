package com.ecat.integration.ModbusIntegration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * 【连接执行者 HELD hold deadline，接替幽灵锁收割的防线语义（原与 serial
 * SerialSourcePortGhostLockReapTest 同型）】release 执行链整体丢失（计时任务入队被拒/
 * 持有线程被 master.send 无界等待吸收）时，旧世界 currentKey 成永久幽灵锁：后续 acquire
 * 永远超时，恢复循环不收敛。执行者形态下无锁可泄漏：持有超阈值由执行者单线程状态机
 * 强制收场（[MODBUS-GHOST-LOCK-REAPED] trigger=hold-deadline），连接随即恢复授予。
 *
 * <p>契约：
 * <ul>
 *   <li>持有时长超过阈值 → 执行者强制收场，下一次 acquire 立即可得，旧 key 的迟到
 *       release 无效；</li>
 *   <li>持有时长未超阈值（可能仍是合法长事务）→ 不得收场。</li>
 * </ul>
 */
public class ModbusGhostLockReapTest {

    private static final long REAP_THRESHOLD_MS = 100;
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

        // 不固定等待跨阈值：收割已发生的确定性信号=排队者被授予（授予前提
        // currentHeld==null 只能由 hold deadline 强制收场达成；排队预算 500ms 远大于
        // 100ms 阈值，收场迟到只会推迟授予不会翻红）
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
