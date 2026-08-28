package com.ecat.integration.ModbusIntegration.Sdk;

import lombok.Value;

/**
 * 单轮轮询的结果快照：SDK 对每轮事务结局的统一分类账。生产代码零消费，但 24 个
 * modbus 族设备仓的 {@code PollingLockBusySkipTest} 回归锁（锁忙跳过契约）与 sensecap
 * 的 sweep 冻结探针经 {@code ModbusPolling.onRound} 消费本类型——R8 复核判「测试消费
 * = 真实消费」保留（生产观测走统一日志/断连状态行）。一次性快照，字段不可变。
 *
 * @author coffee
 */
@Value
public class RoundReport {

    /** 本轮序号（从 1 起单调递增，跨轮不重复）。 */
    long roundIndex;

    /** 本轮结局五分类。 */
    Outcome outcome;

    /** 异常轮的原始异常（剥 CompletionException/ExecutionException 包装后的根因），正常轮为 null。 */
    Throwable error;

    /** 本轮耗时（毫秒，发起段到事务 CF 完成点；跳拍轮为 0）。 */
    long durationMs;

    /**
     * 单轮结局五分类。判定顺序与 SDK 消费语义：
     * <ul>
     *   <li>{@link #SUCCESS} —— 事务正常完成且业务返回 true（计入 PollingHandle.getCompletedRounds）；</li>
     *   <li>{@link #BUSINESS_FALSE} —— 事务正常完成但业务返回 false（SDK 统一 warn：读了但没读成业务态）；</li>
     *   <li>{@link #LOCK_BUSY_SKIPPED} —— 源锁忙本轮跳过（LockBusy 内部消化，不算失败，引擎侧正常完成）；</li>
     *   <li>{@link #TIMED_OUT} —— 本轮超出事务内建硬超时；</li>
     *   <li>{@link #FAILED} —— 传输/业务异常（SDK 统一 error，向引擎异常完成）。</li>
     * </ul>
     */
    public enum Outcome {
        SUCCESS, BUSINESS_FALSE, LOCK_BUSY_SKIPPED, TIMED_OUT, FAILED
    }
}
