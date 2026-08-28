package com.ecat.integration.ModbusIntegration.Sdk;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.ecat.core.Task.runner.ShotScheduler;
import com.ecat.core.Utils.Mdc.TraceContext;
import com.ecat.integration.ModbusIntegration.ModbusTransactionStrategy;

/**
 * modbus SDK 定时缝替身（fake-tier，与 http 域 FakeSdkTimers 同构）：构造即经
 * {@link ModbusSdkTimers#bindForTest} 绑定，捕获 SDK 提交的全部「MDC 包装单发」
 * （周期链每一拍 / 轮超时执法 / {@code delay(ms)} 留隙糖），由测试线程手动驱动——
 * 零后台线程、零真实时钟，断言以「捕获的调用记录」表达（测试纪律：禁 sleep 同步）。
 * SDK 若未走 ModbusSdkTimers 提交，捕获列表为空即失败——定时接线本身在测试面内。
 *
 * <p><b>双流记录（S4 起）</b>：事务策略内建计时（apply 看门狗/事务硬超时）也挂本域
 * 定时器，但其节律与周期链语义正交——记入 {@link #transactionInternalShots}，
 * 不进 {@link #shots}（链断言的「第 N 拍」下标语义保持迁移前口径；事务计时自有
 * 专测覆盖）。提交归属按调用栈首个非基建帧判定（见 {@link #fromTransactionTimer()}）。
 *
 * <p>每条记录持有提交时 MDC 快照（坐标传播断言面）；{@link #fire(int)} 在测试线程执行
 * 已包装命令（提交时上下文被 wrapRunnable 恢复，MDC 断言由此成立）。</p>
 *
 * <p>{@link #close()} 必须 unbind：缝是静态绑定，测试须成对清理防跨测试泄漏。</p>
 */
final class FakeModbusTimers implements ShotScheduler, AutoCloseable {

    /** 单发提交记录：命令（已含 MDC 包装）/延迟毫秒/可取消桩/提交时 MDC 快照。 */
    static final class Shot {
        final Runnable command;
        final long delayMillis;
        final StubFuture future = new StubFuture();
        final Map<String, String> submitMdc;

        Shot(Runnable command, long delayMillis, Map<String, String> submitMdc) {
            this.command = command;
            this.delayMillis = delayMillis;
            this.submitMdc = submitMdc;
        }
    }

    /** 链可见流：周期链每一拍 + SDK 级单发（轮超时执法 / delay 糖）。 */
    final List<Shot> shots = new CopyOnWriteArrayList<>();

    /** 事务内建计时流（apply 看门狗/事务硬超时）：与链断言正交，单独留存可观测。 */
    final List<Shot> transactionInternalShots = new CopyOnWriteArrayList<>();

    FakeModbusTimers() {
        ModbusSdkTimers.bindForTest(this);
    }

    @Override
    public ScheduledFuture<?> fireAfter(Runnable command, long delayMillis) {
        Shot shot = new Shot(command, delayMillis, TraceContext.capture());
        (fromTransactionTimer() ? transactionInternalShots : shots).add(shot);
        return shot.future;
    }

    /**
     * 判定本次提交是否来自事务策略的内建计时（{@link ModbusTransactionStrategy}
     * 的 apply 看门狗 / withHardTimeout——S4 起挂本域定时器）：自栈顶向下找首个
     * 「非定时基建」帧，是事务策略类 ⇒ 事务内建计时；周期链（core runner 包）/
     * SDK 级提交方（ModbusPolling 轮超时执法、delay 糖/测试线程）⇒ 链可见流。
     * 基建帧 = 本替身 + ModbusSdkTimers（含缝 lambda）+ core runner 包 + Mdc 包装。
     */
    private static boolean fromTransactionTimer() {
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String className = frame.getClassName();
            if (className.equals(ModbusTransactionStrategy.class.getName())) {
                return true;
            }
            if (className.equals(FakeModbusTimers.class.getName())
                    || className.startsWith(ModbusSdkTimers.class.getName())
                    || className.startsWith("com.ecat.core.Task.runner.")
                    || className.startsWith("com.ecat.core.Utils.Mdc.")) {
                continue;
            }
            return false;
        }
        return false;
    }

    @Override
    public void close() {
        ModbusSdkTimers.unbindForTest();
    }

    /** 手动触发第 i 个单发（模拟到拍；命令在测试线程以提交时 MDC 执行）。 */
    void fire(int index) {
        shots.get(index).command.run();
    }

    /** 最近一条提交记录。 */
    Shot lastShot() {
        return shots.get(shots.size() - 1);
    }

    /** 可取消的最小 ScheduledFuture 桩：只承载 cancel/isCancelled 语义。 */
    static final class StubFuture implements ScheduledFuture<Object> {
        volatile boolean cancelled;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public Object get() {
            throw new UnsupportedOperationException("测试桩不支持取值");
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException("测试桩不支持取值");
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0L;
        }

        @Override
        public int compareTo(java.util.concurrent.Delayed other) {
            return 0;
        }
    }
}
