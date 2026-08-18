package com.ecat.integration.ModbusIntegration;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.ecat.core.Utils.LogFactory;
import com.ecat.core.Utils.Log;

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

            try {
                CompletableFuture<Boolean> operations = lambda.apply(source);

                return operations.whenComplete((res, ex) -> {
                    try {
                        if (ex != null) {
                            log.error("Error during Modbus operations: " + ex.getMessage());
                        }
                    } finally {
                        source.release(key);
                    }
                });
            } catch (Exception e) {
                source.release(key);
                CompletableFuture<Boolean> failedFuture = new CompletableFuture<>();
                failedFuture.completeExceptionally(e);
                return failedFuture;
            }
        } else {
            log.error("Failed to acquire lock, modbusInfo: " + source.getModbusInfo().toString() + ", maxWaiters: " + source.getMaxWaiters()
                    + ", currentWaitingCount: " + source.getWaitingCount());
            CompletableFuture<Boolean> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new IllegalStateException("Failed to acquire lock"));
            return failedFuture;
        }
    }
}    
