package com.ecat.integration.ModbusIntegration.Sdk;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import com.ecat.core.Task.runner.FireDecision;
import com.ecat.core.Task.runner.RoundSchedule;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;

/**
 * modbus 轮询网格策略（29 号 v2 S1：网格知识归域，core PeriodicChain 只按链事件取延迟；
 * 与 http 域 PollingSchedule 同构，差异仅首拍——modbus 保留公共 API
 * {@code initialDelay(D)}，首拍延迟与名义锚点都从 D 起算）。内部以注入纳米钟维护名义
 * 锚点：
 * <ul>
 *   <li>fixedDelay：结算点+period=下一拍（完成点重排，天然防重叠）；</li>
 *   <li>fixedRate：名义网格+period 推进，在飞期间跨过的拍跳过（推进到首个未来网格点，
 *       不做滞后补跑——补跑=同任务重入，正是要消灭的形态）；</li>
 *   <li>到拍滞后超过一个整周期→过期即弃：本轮不触碰轮体，锚点按
 *       skips = lag / period + 1 推进到首个未来网格点后重排。</li>
 * </ul>
 * 锚点仅链上串行读写（单拍在飞时不被触碰，无跨线程竞态）；默认首发即发（D=0，锚点=
 * 起链时刻，对位原引擎 initialDelay=0 默认）。
 */
final class ModbusPollingSchedule implements RoundSchedule {

    private static final Log log = LogFactory.getLogger(ModbusPollingSchedule.class);

    private final boolean fixedRate;
    private final long periodNanos;
    private final long firstDelayMillis;
    private final LongSupplier nanoClock;
    private final String targetName;

    /** 名义网格锚点（nanoClock 基，起链时刻+initialDelay 锚定）。 */
    private long nominalNanos;

    private ModbusPollingSchedule(boolean fixedRate, long periodMs, long initialDelayMs,
            LongSupplier nanoClock, String targetName) {
        if (periodMs <= 0L) {
            throw new IllegalArgumentException("period 必须为正（毫秒）: " + periodMs);
        }
        if (initialDelayMs < 0L) {
            throw new IllegalArgumentException("initialDelay 不得为负（毫秒）: " + initialDelayMs);
        }
        this.fixedRate = fixedRate;
        this.periodNanos = TimeUnit.MILLISECONDS.toNanos(periodMs);
        this.firstDelayMillis = initialDelayMs;
        this.nanoClock = nanoClock;
        this.targetName = targetName;
        this.nominalNanos = nanoClock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(initialDelayMs);
    }

    /** fixedDelay 语义：完成点+period=下轮。 */
    static ModbusPollingSchedule fixedDelay(long periodMs, long initialDelayMs,
            LongSupplier nanoClock, String targetName) {
        return new ModbusPollingSchedule(false, periodMs, initialDelayMs, nanoClock, targetName);
    }

    /** fixedRate 语义：名义网格发射、到拍在飞跳拍（aogan/ebyte/epever 等 FixedRate 族对位）。 */
    static ModbusPollingSchedule fixedRate(long periodMs, long initialDelayMs,
            LongSupplier nanoClock, String targetName) {
        return new ModbusPollingSchedule(true, periodMs, initialDelayMs, nanoClock, targetName);
    }

    @Override
    public long firstDelayMillis() {
        return firstDelayMillis;
    }

    @Override
    public FireDecision onFire() {
        long now = nanoClock.getAsLong();
        long lag = now - nominalNanos;
        if (lag <= periodNanos) {
            return FireDecision.run();
        }
        // 过期即弃（原引擎 SCHED_STALE_DROP）：到拍滞后超过一个周期，本轮数据无意义
        long skips = lag / periodNanos + 1L;
        nominalNanos += skips * periodNanos;
        log.info("ModbusPolling {} 到拍滞后超过一个周期（{}ms），本轮丢弃重排下一周期",
                targetName, lag / 1_000_000L);
        return FireDecision.drop(delayMillis(now));
    }

    @Override
    public long onSettleRearmMillis() {
        long now = nanoClock.getAsLong();
        if (!fixedRate) {
            nominalNanos = now + periodNanos;
        } else {
            nominalNanos += periodNanos;
            if (nominalNanos <= now) {
                long skips = (now - nominalNanos) / periodNanos + 1L;
                nominalNanos += skips * periodNanos;
            }
        }
        return delayMillis(now);
    }

    private long delayMillis(long now) {
        return Math.max(0L, (nominalNanos - now) / 1_000_000L);
    }
}
