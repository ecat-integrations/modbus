package com.ecat.integration.ModbusIntegration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.ecat.core.Task.NamedThreadFactory;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import com.ecat.core.Utils.Mdc.MdcExecutorService;

/**
 * Modbus IO 旁池（R3 期 4，15 号设计 §6.4——D4 已批准）：modbus 阻塞事务体
 * （{@code master.send} 含写+等回音）的专职有界定长池，与调度引擎车道彻底分离——
 * 引擎 worker 回归「发起+CPU」职责（发起即返），「等响应」的阻塞消耗由本池的
 * {@code ecat-modbus-io-0..N-1} daemon 线程承接。数十源并发轮询下 6 worker 被
 * 响应窗逐一钉死（084500 风暴族）的形态自此失去形成条件。
 *
 * <p><b>≠lane 队列</b>：本池不进引擎 queuedTotal/熔断/coalesce 治理（车道治理不适用）；
 * 同连接串行由 {@link ModbusSource} 的提交面单飞（per-source drain，见其 dispatchIo）保证，
 * 不依赖本池的调度形态——同源任意时刻至多占一个池线程，事务内并行段不放大池占用。
 *
 * <p><b>有界 + 突发吸收 + 饱和即拒</b>：定容 N 线程 + 有界排队（{@code 4×N}）——冷启动
 * 全源首帧齐发（~57 源的 drain 任务并发提交）在窗内排队消化、不失败；真饱和（N 线程全忙
 * 且队满）才抛 {@link java.util.concurrent.RejectedExecutionException}（AbortPolicy；不用
 * CallerRunsPolicy——那会把阻塞弹回发起线程，重新钉死 worker）。拒绝语义 = 事务级
 * 「过期即弃」：轮询本周期放弃下周期再试 / 写闸记 FAILED，不阻塞发起段
 * （live 实证定形：零排队的 SynchronousQueue 形态在冷启动风暴窗即触发拒绝——突发吸收
 * 是有界性的组成部分，不是无界化）。
 *
 * <p><b>生命周期</b>：生产由 {@link ModbusIntegration#onInit} 按配置 {@code io_pool_size}
 * （1..64，默认 {@link Const#DEFAULT_IO_POOL_SIZE}）初始化、{@code onRelease} 关停
 * （graceful——在途 send 自然跑完）。停机是<b>终端态</b>（R-F：对齐 serial/tcp/http
 * 三域）——关停后取用 {@link #executor()} 抛 {@link java.util.concurrent.RejectedExecutionException}，
 * 不再惰性重建；同 JVM 显式重初始化（onInit→initialize，生产 owner 责任）可重开窗口。
 * 无集成上下文（单测/独立运行）的<b>首次</b>取用仍按默认容量惰性建池（生产中集成必先于
 * 设备就绪，不落此分支）。MDC 经 {@link MdcExecutorService} 提交时捕获传播（与既有车道同权）。
 *
 * @author coffee
 */
public final class ModbusIoPool {

    private static final Log log = LogFactory.getLogger(ModbusIoPool.class);

    /** 当前旁池（volatile 无锁读快路径；init/shutdown 在类锁内写）。 */
    private static volatile ExecutorService pool;

    /** 停机终端态标志：置位后 executor() 拒绝、不再惰性建池（严格模式，停机不自动复活）。 */
    private static volatile boolean terminated;

    private ModbusIoPool() {
    }

    /**
     * 按给定容量初始化旁池（生产：ModbusIntegration.onInit 消费 io_pool_size 配置）。
     * 已有 live 池时先 graceful shutdown 再重建（重复 onInit / 容量变更不泄漏线程；
     * 重建期间旧池在途 send 自然跑完，新提交走新池）。本入口是停机终端态后唯一合法的
     * 重开方式（显式生产 owner 动作——同 JVM 集成重初始化），区别于旧「取用时静默懒重建」。
     *
     * @param poolSize 池容量（线程数）；1..64 由 ConfigDefinition 校验保证，
     *                 非法值当场拒绝（严格模式，不静默钳制）
     */
    public static synchronized void initialize(int poolSize) {
        if (poolSize <= 0) {
            throw new IllegalArgumentException("io_pool_size must be > 0, got: " + poolSize);
        }
        ExecutorService previous = pool;
        if (previous != null && !previous.isShutdown()) {
            log.info("Modbus IO pool re-initialized to size " + poolSize + ", previous pool draining gracefully");
            previous.shutdown();
        }
        pool = activate(poolSize);
        terminated = false;
        log.info("Modbus IO pool initialized: ecat-modbus-io-0.." + (poolSize - 1)
                + " (" + poolSize + " daemon threads)");
    }

    /**
     * 取旁池执行器：无 live 池时按默认容量惰性建池——无集成上下文的单测/独立运行<b>首次</b>
     * 取用边界（生产路径必先经 ModbusIntegration.onInit 初始化，不落此分支）。
     * 停机终端态后取用显式拒绝（R-F：对齐 serial/tcp/http 三域，不再静默懒重建）。
     *
     * @return live 旁池（永不返回已关停的池引用）
     * @throws java.util.concurrent.RejectedExecutionException 已停机（终端态）
     */
    public static ExecutorService executor() {
        if (terminated) {
            throw new RejectedExecutionException("modbus IO pool terminated (onRelease terminal state)");
        }
        ExecutorService current = pool;
        if (current != null && !current.isShutdown()) {
            return current;
        }
        synchronized (ModbusIoPool.class) {
            if (terminated) {
                throw new RejectedExecutionException("modbus IO pool terminated (onRelease terminal state)");
            }
            current = pool;
            if (current == null || current.isShutdown()) {
                log.info("无集成上下文（单测/独立运行），modbus IO 旁池按默认容量 "
                        + Const.DEFAULT_IO_POOL_SIZE + " 惰性创建");
                current = activate(Const.DEFAULT_IO_POOL_SIZE);
                pool = current;
            }
            return current;
        }
    }

    /**
     * 关停旁池（生产：ModbusIntegration.onRelease，在全部 source 销毁之后调用）。
     * graceful（{@code shutdown()}）：在途 send 跑完，新提交拒绝；<b>终端态</b>——此后
     * {@link #executor()} 抛 REE，不再惰性重建（重开=显式 {@link #initialize}）。
     */
    public static synchronized void shutdown() {
        terminated = true;
        ExecutorService current = pool;
        if (current != null && !current.isShutdown()) {
            current.shutdown();
            log.info("Modbus IO pool shut down (terminal)");
        }
        pool = null;
    }

    /**
     * 复位到未建池状态（仅测试基建：隔离其他测试类经 onRelease/shutdown 关池的顺序影响
     * ——生产停机是终端态，只有测试能重开窗口）。同包测试消费，包内可见。
     */
    static void resetForTest() {
        synchronized (ModbusIoPool.class) {
            ExecutorService current = pool;
            if (current != null && !current.isShutdown()) {
                current.shutdownNow();
            }
            pool = null;
            terminated = false;
        }
    }

    /**
     * 排队容量倍数（4×线程数）：冷启动全源首帧齐发的突发窗吸收（57 源 × 每源 1 个 drain
     * 任务 &lt; 16 线程 + 64 排队 = 80 容量），稳态并发（个位数活跃事务）远不触及——
     * 队满才是真饱和（拒绝即「过期即弃」）。
     */
    private static final int QUEUE_CAPACITY_FACTOR = 4;

    /**
     * 建池 + 激活：返回 MDC 包装视图（提交时捕获、事务执行时恢复 TraceContext）。
     */
    private static ExecutorService activate(int poolSize) {
        return MdcExecutorService.wrap(newPool(poolSize));
    }

    /**
     * 建池：定容 N 线程 + 有界排队（{@link #QUEUE_CAPACITY_FACTOR}×N，突发吸收）+
     * AbortPolicy（队满即拒）+ daemon 命名线程（ecat-modbus-io-N）。
     */
    private static ThreadPoolExecutor newPool(int poolSize) {
        return new ThreadPoolExecutor(poolSize, poolSize,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY_FACTOR * poolSize),
                new NamedThreadFactory("ecat-modbus-io", true),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
