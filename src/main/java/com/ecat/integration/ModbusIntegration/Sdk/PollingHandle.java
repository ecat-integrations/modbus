package com.ecat.integration.ModbusIntegration.Sdk;

/**
 * {@link ModbusPolling#start()} 返回的轮询生命周期句柄：设备仓自存并在 onStop 里
 * {@link #cancel()}（对齐既有 {@code readFuture.cancel} 语义）；{@link #isRunning()}
 * 与 cancel 对偶（句柄词汇完整性）。
 *
 * <p>观测口径：{@link #getCompletedRounds()} 是本 polling 实例的成功轮数（业务返回
 * true 的轮；sensecap 的 sweep 冻结探针在消费——R8 复核判「测试消费=真实消费」保留）；
 * 锁忙跳拍观测走源级 {@code ModbusSource.getLockBusySkipCount()}（跨 polling 实例
 * 生命周期连续——同一共享源上多台设备的跳拍观测不因句柄替换清零），不经句柄透出。
 *
 * @author coffee
 */
public interface PollingHandle {

    /** 停止本轮询（幂等；不中断在飞事务——事务 CF 自行完成并释放源锁）。 */
    void cancel();

    /** 轮询是否仍在周期网格上（未 cancel 且任务未终止）。 */
    boolean isRunning();

    /** 成功轮数（业务返回 true 的轮，本实例口径）。 */
    long getCompletedRounds();
}
