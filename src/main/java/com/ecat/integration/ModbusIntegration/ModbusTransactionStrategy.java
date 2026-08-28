package com.ecat.integration.ModbusIntegration;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import com.ecat.core.Task.LockBusySkippedException;
import com.ecat.core.Utils.LogFactory;
import com.ecat.core.Utils.Log;
import com.ecat.integration.ModbusIntegration.Sdk.ModbusSdkTimers;

/**
 * The {@code ModbusTransactionStrategy} class provides a mechanism to execute operations
 * in a Modbus manner using a locking strategy. It ensures that operations on a shared
 * resource are performed sequentially by acquiring and releasing a lock.
 *
 * <p>This class is designed to work with asynchronous operations using {@link CompletableFuture}.
 * It allows a lambda function to be executed with a {@link ModbusSource}, ensuring that the
 * lock is properly released after the operation is completed, even in the case of exceptions.
 *
 * <p>Usage example:
 * <pre>
 * {@code
 * ModbusSource source = ...;
 * CompletableFuture<Boolean> result = ModbusTransactionStrategy.executeWithLambda(
 *     source,
 *     src -> CompletableFuture.supplyAsync(() -> {
 *         // Perform operations with the source
 *         return true;
 *     })
 * );
 * }
 * </pre>
 *
 * <p>Key features:
 * <ul>
 *   <li>Ensures that the lock is acquired before executing the operation.</li>
 *   <li>Releases the lock after the operation is completed or if an exception occurs.</li>
 *   <li>Logs errors and thread information for debugging purposes.</li>
 *   <li>Handles exceptions gracefully by returning a failed {@link CompletableFuture}.</li>
 * </ul>
 *
 * @see ModbusSource
 * @see CompletableFuture
 * 
 * @author coffee
 */
public class ModbusTransactionStrategy {

    private static final Log log = LogFactory.getLogger(ModbusTransactionStrategy.class);

    /**
     * 周期任务内同步等待单次 modbus 读 future 的有界等待倍数（103000 根因修复②）。
     *
     * <p>派生依据：单次 {@code master.send} 的既有时延上界 = 请求超时 ×（内置重试+1）量级
     * （{@link ModbusSource#getRequestTimeoutMs()}，即 {@code master.setTimeout} 的取值）；
     * 本倍数在其上再翻倍留余量，覆盖读 future 所在执行器车道的排队调度延迟。目标是把
     * 「无界 join/get 在车道饥饿时无限钉死调用线程（生产 jstack 直证钉死引擎 worker）」
     * 变为有界等待、超时走调用方失败路径——不是精确的重试预算。
     */
    public static final int BOUNDED_READ_WAIT_FACTOR = 6;

    /**
     * 解析周期任务内同步等待单次 modbus 读的有界等待上限（毫秒）：
     * {@code source.getRequestTimeoutMs() × BOUNDED_READ_WAIT_FACTOR}。
     *
     * @param source 读请求所属的 modbus 源（非 null；同步等待者必然已持有源引用）
     * @return 有界等待上限（毫秒），恒为正
     */
    public static long boundedReadWaitMs(ModbusSource source) {
        return (long) source.getRequestTimeoutMs() * BOUNDED_READ_WAIT_FACTOR;
    }

    public static CompletableFuture<Boolean> executeWithLambda(ModbusSource source, Function<ModbusSource, CompletableFuture<Boolean>> lambda) {
        String key = source.acquire();
        if (key!=null) {
            return executeHeld(source, key, lambda);
        } else {
            log.error("Failed to acquire lock, modbusInfo: " + source.getModbusInfo().toString() + ", maxWaiters: " + source.getMaxWaiters()
                    + ", currentWaitingCount: " + source.getWaitingCount());
            CompletableFuture<Boolean> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new IllegalStateException("Failed to acquire lock"));
            return failedFuture;
        }
    }

    /**
     * 轮询事务入口（E2/R3 终态修复，方案 c：acquire 非阻塞化——调度三原则「过期即弃」，
     * 与 serial {@code SerialTransactionStrategy.executePolling} 同型）。
     *
     * <p>与 {@link #executeWithLambda(ModbusSource, Function)} 的唯一差异在锁忙分支：经
     * {@link ModbusSource#tryAcquire()} 非阻塞取锁——锁忙时<b>本周期立即放弃</b>（不 park
     * 等锁、不占等待队列、不消费 signal），返回以
     * {@link LockBusySkippedException} 异常完成的
     * future。周期任务的 whenComplete 消费方应把该异常识别为「本轮跳过」而非设备错误。
     * 放弃在源侧有记账（{@link ModbusSource#getLockBusySkipCount()} + 限频 warn）。
     *
     * <p><b>完成语义</b>：fire-and-forget（事务 CF 由调用方 whenComplete 消费，阻塞 send
     * 已由 IO 旁池承接，见 ModbusSource.dispatchIo）。曾按 R3 期 4 计划把事务 CF 挂引擎
     * 周期任务（引擎静态 attach 钩子），R4 全异步统一模型（用户终批）废止 attach 机制后
     * 已回退且钩子已在 R4 批 0 删除——轮询完成绑定由调用方经
     * {@code scheduleWithFixedDelay(Supplier, ...)} 重载（事务 CF 返回值流出）接入，
     * 本方法保持 fire-and-forget 现状。
     *
     * <p><b>边界</b>：只供周期轮询任务体使用；属性写（setValueImpl 钩子的 IO 事务体，22 号 setValue final 化后）与需要有限
     * 等待语义的调用方继续走 {@code executeWithLambda}（闸内 IO 体对锁的等待保留）。
     *
     * <p>取锁成功后的事务体/硬超时/release/传输强拆链路与 {@code executeWithLambda} 完全
     * 共享（{@link #executeHeld}），F-16 的收割/恢复机制不受影响。
     *
     * @param source modbus 源
     * @param lambda 在持锁期间执行的事务
     * @return 事务结果 future；锁忙时为 LockBusySkippedException 异常 future（立即完成）
     */
    public static CompletableFuture<Boolean> executePolling(ModbusSource source, Function<ModbusSource, CompletableFuture<Boolean>> lambda) {
        String key = source.tryAcquire();
        if (key == null) {
            CompletableFuture<Boolean> skipped = new CompletableFuture<>();
            skipped.completeExceptionally(new LockBusySkippedException(
                    "Polling transaction skipped: source lock busy, will retry next cycle"));
            return skipped;
        }
        return executeHeld(source, key, lambda);
    }

    /**
     * 已持锁事务体的共享执行链（executeWithLambda 与 executePolling 的取锁后公共路径）：
     * apply 阶段看门狗 + 事务级硬超时 + whenComplete 内联 release + 超时强拆
     * （Q-1/A2 修复语义原样保留，F-40 补 apply 阶段盲区）。
     *
     * <p><b>F-40 apply 阶段看门狗</b>：写系列事务体（writeXxxWithSlaveId）在调用线程直发
     * {@code master.send}（M8 单飞队列自锁修复的合法形态），TCP 传输半开时 send 永不返回，
     * 本方法阻塞在 {@code lambda.apply} 内部——既有事务硬超时在 apply 返回后才武装，对该
     * 挂死形态完全不覆盖（ASM G11-5：控制写挂死 → 源锁钉死 → 轮询 tryAcquire 全放弃 →
     * 拥塞窗 acquire 超时雪崩）。看门狗在 apply 前武装、apply 返回即撤销，超时未返回则
     * 释放锁 + 强拆传输（socket 关闭使阻塞 send 异常返回）+ future 异常完成，与既有
     * 硬超时路径同一恢复动作（release 先于强拆，防 recovery 阻塞推迟锁释放）。
     */
    private static CompletableFuture<Boolean> executeHeld(ModbusSource source, String key,
            Function<ModbusSource, CompletableFuture<Boolean>> lambda) {
            final long hardTimeoutMs = boundedReadWaitMs(source);
            final CompletableFuture<Boolean> result = new CompletableFuture<>();
            // apply 阶段看门狗：apply 超时未返回 = 写直发 send 挂死强证据。
            // 与 withHardTimeout 同一契约：timeout 必须 > 0（生产 requestTimeoutMs 恒正；
            // 0/负值不武装——0ms 看门狗会在 apply 尚未返回时立即误触发强拆）。
            final java.util.concurrent.ScheduledFuture<?> applyWatchdog = hardTimeoutMs > 0
                    ? scheduleApplyWatchdog(source, key, hardTimeoutMs, result)
                    : null;
            try {
                CompletableFuture<Boolean> operations = lambda.apply(source);
                if (applyWatchdog != null) {
                    // apply 已返回：看门狗使命结束（后续挂死形态由 withHardTimeout 界住）
                    applyWatchdog.cancel(false);
                }
                // Q-1/A2 修复（与 serial B5 同型补强）：事务体是 supplyAsync(() -> master.send(...))，
                // 底层 socket/串口写挂死时 send 永不返回（master.setTimeout 只约束等待响应路径），
                // future 永不 complete → release 永不执行 → 源锁成幽灵锁（P0 验收 A2 实证：
                // sim 存活的 modbus TCP 1504 锁同样 Acquire timeout ... currentWaitingCount:0）。
                // 事务级硬超时保证 future 必然 complete → release 必执行。
                CompletableFuture<Boolean> withTimeout = withHardTimeout(operations, hardTimeoutMs);
                withTimeout.whenComplete((res, ex) -> {
                    // applyToEither 依赖链可能把 TimeoutException 包成 CompletionException，解包判断
                    Throwable root = (ex instanceof CompletionException && ex.getCause() != null) ? ex.getCause() : ex;
                    try {
                        if (ex != null) {
                            log.error("Error during Modbus operations: " + ex.getMessage());
                        }
                    } finally {
                        if (root instanceof TimeoutException) {
                            // 硬超时 = 底层传输挂死强证据：强拆传输原语（TCP socket / RTU 串口），
                            // 救活钉死的车道线程（仅释放锁、车道仍死的话后续事务照样排队挂死）。
                            // Q-1/Q-2 二轮：release 必须先于 recovery——recovery 含 destroy/init 等
                            // 可能阻塞的动作，若在中途阻塞会无限期推迟 release，currentKey 直接
                            // 升级为幽灵锁（live 实证持锁 45min+）。锁释放与传输强拆无顺序依赖。
                            // S4：超时胜出时本 whenComplete 跑在域 sched 计时线程上，强拆经
                            // submitTransportRecovery 卸载 ModbusIoPool（定容契约）。
                            source.release(key);
                            submitTransportRecovery(source, "transaction-hard-timeout", key);
                        } else {
                            source.release(key);
                        }
                    }
                }).whenComplete((res, ex) -> {
                    // 透传到对外 future（watchdog 已异常完成时此处 no-op，不覆盖恢复结论）
                    if (ex != null) {
                        result.completeExceptionally(ex);
                    } else {
                        result.complete(res);
                    }
                });
                return result;
            } catch (Exception e) {
                if (applyWatchdog != null) {
                    applyWatchdog.cancel(false);
                }
                source.release(key);
                result.completeExceptionally(e);
                return result;
            }
    }

    /**
     * apply 阶段看门狗（F-40）：到点时 apply 仍未返回（写直发 send 挂死）——
     * release 先于强拆（与硬超时路径同序），随后强拆传输原语使阻塞线程异常得救，
     * 并把对外 future 以 TimeoutException 异常完成（调用方不再无界等）。
     * 计时经域自持 {@link ModbusSdkTimers#fireAfter}（S4 已落地，与
     * {@link #withHardTimeout} 同一计时面）；到点体保持 µs 级——强拆经
     * {@link #submitTransportRecovery} 卸载 ModbusIoPool，不钉死 sched 定容线程。
     *
     * @return 计时任务句柄；调度器拒绝提交时返回 null（warn 记账，apply 覆盖降级，
     *         读路径仍由 withHardTimeout 兜底）
     */
    private static java.util.concurrent.ScheduledFuture<?> scheduleApplyWatchdog(
            ModbusSource source, String key, long hardTimeoutMs, CompletableFuture<Boolean> result) {
        try {
            return ModbusSdkTimers.fireAfter(() -> {
                log.error("[MODBUS-APPLY-WEDGE-RECOVERY] 事务体在 apply 阶段阻塞超过 {}ms"
                                + "（写直发 master.send 挂死强证据），释放锁+强拆传输, key: {}, modbusInfo: {}",
                        hardTimeoutMs, key, source.getModbusInfo());
                source.release(key);
                // 先完成对外 future（强拆已异步化，不再让调用方等恢复动作）
                result.completeExceptionally(new TimeoutException(
                        "Transaction body wedged in apply after " + hardTimeoutMs + " ms"));
                submitTransportRecovery(source, "transaction-apply-wedge", key);
            }, hardTimeoutMs);
        } catch (RuntimeException scheduleEx) {
            log.warn("Apply watchdog submission failed (scheduler rejected?), apply-phase wedge "
                    + "coverage degraded for key " + key + ": " + scheduleEx.getMessage());
            return null;
        }
    }

    /**
     * 事务级硬超时（Q-1/A2，与 serial {@code SerialTransactionStrategy.withHardTimeout} 同型）：
     * 保证返回的 future 必然在 {@code timeoutMs} 内 complete（成功透传，或异常
     * {@link TimeoutException}），从而 {@code whenComplete} 必触发 → release 必执行。
     *
     * <p>Java 8 无 {@code orTimeout}，用 {@code applyToEither} 叠加由域自持定时器
     * （{@link ModbusSdkTimers#fireAfter}，MDC 单发、毫秒精度）到点异常完成的计时 future
     * 实现。S4 已落地：本方法与 {@link #scheduleApplyWatchdog} 是 modbus 对
     * {@code ModbusSdkTimers#fireAfter} 的事务计时消费面（core
     * {@code Task.execution.TimeoutScheduler} 已整删）；到点体仅 complete future
     * （µs 级），强拆恢复在调用侧 whenComplete 里经
     * {@link #submitTransportRecovery} 卸载 ModbusIoPool。
     *
     * @param future    原事务 future（挂死传输下可能永不 complete）
     * @param timeoutMs 硬超时（毫秒），调用方保证 &gt; 0（boundedReadWaitMs 恒为正）
     * @return 必然 complete 的 future
     */
    static CompletableFuture<Boolean> withHardTimeout(CompletableFuture<Boolean> future, long timeoutMs) {
        CompletableFuture<Boolean> timer = new CompletableFuture<>();
        ScheduledFuture<?> scheduled;
        try {
            scheduled = ModbusSdkTimers.fireAfter(
                    () -> timer.completeExceptionally(new TimeoutException(
                            "Transaction hard timeout after " + timeoutMs + " ms")),
                    timeoutMs);
        } catch (RuntimeException scheduleEx) {
            // 计时任务提交失败（如调度器排队达上限拒绝）：timer 永不完成 → withHardTimeout 永不
            // 完成 → release 永不执行 → 源锁成幽灵锁（Q-1/Q-2 二轮根因之一）。立即异常完成
            // timer 保证 release 必达；非 TimeoutException 不误触发传输强拆。
            timer.completeExceptionally(new IllegalStateException(
                    "Hard-timeout timer submission failed, failing fast to guarantee release", scheduleEx));
            return future.applyToEither(timer, Function.identity());
        }
        // 引擎拒绝路径可能返回已异常完成的 future（任务体永不执行）——提交后立即复查，
        // 已异常完成则同样 fail-fast（与 serial 同型）。
        if (scheduled.isDone()) {
            try {
                scheduled.get();
            } catch (java.util.concurrent.ExecutionException rejected) {
                timer.completeExceptionally(new IllegalStateException(
                        "Hard-timeout timer rejected, failing fast to guarantee release", rejected.getCause()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // 原 future 先完成时取消计时任务；计时先到时对已执行的 schedule cancel 为 no-op
        return future.applyToEither(timer, Function.identity())
                .whenComplete((res, ex) -> scheduled.cancel(false));
    }

    /**
     * 传输强拆的统一卸载入口（S4，19 号 v2）：{@code forceRecoverTransport} 含 destroy/init
     * 等可阻塞动作，而两处调用点（apply 看门狗到点体 / 硬超时 whenComplete 超时胜出分支）
     * 在超时触发时都跑在域 sched 计时线程上（{@link ModbusSdkTimers} 仅 2 条，零阻塞
     * 定容论证）——恢复动作一律提交 {@link ModbusIoPool} 执行（阻塞落在 IO 旁池线程，
     * 与 master.send 同池同语义）。调用点须先完成 {@code release}（锁释放与强拆无顺序
     * 依赖，release 先于强拆提交保序）。
     *
     * <p>提交被拒（池停机/队满）仅 warn 记账：与既有 inline 强拆失败同降级
     * （挂死传输由下一超时路径重试强拆），不影响调用点已完成的锁释放与 future 终态。
     *
     * @param source 强拆目标源
     * @param reason 强拆原因（日志定位词汇：transaction-hard-timeout / transaction-apply-wedge）
     * @param key    事务持锁 key（仅日志定位）
     */
    private static void submitTransportRecovery(ModbusSource source, String reason, String key) {
        try {
            ModbusIoPool.executor().execute(() -> {
                try {
                    source.forceRecoverTransport(reason);
                } catch (Exception recoverEx) {
                    log.warn("Transport wedge recovery failed for key " + key + ": " + recoverEx.getMessage());
                }
            });
        } catch (RejectedExecutionException submitEx) {
            log.warn("Transport wedge recovery submission failed for key " + key
                    + " (io pool rejected?): " + submitEx.getMessage());
        }
    }
}    
