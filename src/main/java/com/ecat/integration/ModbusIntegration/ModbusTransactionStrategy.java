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
