package com.ecat.integration.ModbusIntegration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.ecat.core.Task.NamedThreadFactory;

/**
 * 连接执行者专用计时器：HELD 排队等待 / hold deadline / 帧 deadline 三类单发。
 *
 * <p><b>为什么不经 {@link ModbusSdkTimers}</b>：SdkTimers 的 bindForTest 缝服务于
 * <b>事务语义计时</b>的测试控制（策略层硬超时/轮周期链要被替身定拍）；执行者三类
 * deadline 是内部执法（等待超时回 null、持有超时强制收场、帧硬超时强拆），被测行为
 * 就是真实墙钟到期——若同走可伪造面，绑定替身的 Sdk 测试（ModbusPollingChain 等）里
 * 排队等待永不到期即假死。专用真时钟使执行者执法与测试替身解耦（serial 同位存在
 * 独立的 SerialTimeoutScheduler 入口，同一解耦思路）。
 *
 * <p><b>池尺寸=2 论证</b>：到点体全部 µs 级（complete + 投事件，强拆为 IO 池卸载提交）；
 * 排队中的计时不占线程（STPE 延迟队列），最坏尖峰=全连接 wait/hold/frame 同时到点
 * （数十个 µs 任务），2 条 daemon 线程亚毫秒吸干；单条被异常到点体钉死时其余链仍在
 * 另一条上准点。域内唯一实例（static，随类加载存活；daemon 不阻 JVM 退出）。
 *
 * @author coffee
 */
final class ModbusExecutorTimers {

    private static final ScheduledExecutorService POOL =
            Executors.newScheduledThreadPool(2, new NamedThreadFactory("ecat-modbus-exec-timer", true));

    private ModbusExecutorTimers() {
    }

    /**
     * 到点单发（毫秒精度，STPE 延迟队列不占线程；不晚于声明的 deadline 触发）。
     * 调用方以返回句柄 {@code cancel(false)} 撤销（STPE 取消即出队）。
     */
    static ScheduledFuture<?> fireAfter(Runnable command, long delayMillis) {
        return POOL.schedule(command, delayMillis, TimeUnit.MILLISECONDS);
    }
}
