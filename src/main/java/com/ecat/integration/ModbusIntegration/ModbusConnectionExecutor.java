package com.ecat.integration.ModbusIntegration;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ScheduledFuture;

import com.ecat.core.CommTrace.ResourceOwner;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.msg.ModbusMessage;
import com.serotonin.modbus4j.msg.ModbusRequest;
import org.slf4j.MDC;

/**
 * 每连接串行执行者（serial 4.1.0 {@code SerialPortExecutor} 同模式、modbus 连接域独立
 * 实现——统一=模式一致非代码复刻）。一个连接（TCP ip:port / RTU portName，仲裁键与
 * {@code ModbusIntegration.getConnectionIdentity} 同口径）一个守护线程状态机，取代
 * 旧「ReentrantLock + Condition + waitQueue + currentKey + 幽灵锁收割 + pendingSends
 * 手写单飞」九件套：排队、互斥、帧序全部收敛为「单线程推进 + 事件邮箱」，无锁可泄漏
 * （release 链丢失由 deadline 强制收场兜底），无幽灵锁可收割（收割机制连同其伴生件
 * 慢持锁打点整体退役）。
 *
 * <p><b>状态机</b>（对连接的两类正交资源各自推进，事件驱动、全部在执行者线程迁移）：
 * <ul>
 *   <li>事务槽（HELD）：{@code IDLE → HELD(授予 acquire) → IDLE(release/deadline)}。
 *       同刻至多一个持有者；授予条件=无当前持有者（与帧状态无关——旧锁授予从不等待
 *       帧，无锁裸帧路径 mgd 形态合法，此处忠实保持）。</li>
 *   <li>线槽（帧）：{@code IDLE → AWAIT_RESPONSE(一帧在 ModbusIoPool worker 上阻塞
 *       send) → IDLE(send 完成 CF 回投)}；旁路 RECOVERING（帧 deadline 强拆后等在飞
 *       send 死亡确认，期间不派下一帧——防止新帧与垂死帧在线上碰撞）。同刻至多一帧
 *       在飞，帧队列无界 FIFO（见下）。</li>
 * </ul>
 *
 * <p><b>与 serial 执行者的刻意差异</b>（不逐条复刻的原因）：
 * <ul>
 *   <li><b>无 DRAINING</b>：modbus4j 按事务 id（TCP MBAP）/请求编码匹配应答，线上无
 *       代际问题；且本执行者从不直接写线（阻塞 send 在 IO 池 worker），不存在
 *       「异常结束后线路残留半帧」的形态——排空在 modbus 域无对应物。</li>
 *   <li><b>无 CLOSED 终态拒绝</b>：master 生命周期归 {@code ModbusSource}/
 *       {@code ModbusMasterFactory}（destroyResources），销毁后帧照常派发、经传输
 *       异常以 null 完成（与旧单飞逐字节同面）；{@link #markClosed()} 只让空闲执行者
 *       线程退出（防源销毁后线程滞留泄漏），有新事件经 {@code post} 按需重启。</li>
 *   <li><b>帧队列无界</b>：旧 pendingSends 即无界 ArrayDeque；有界化会让事务内并行段
 *       （saimosen allOf 四段读、tyxdgroup thenCombine 双读）在容量 3 下拒收——语义
 *       回退，故帧侧不引入容量（有界性由 HELD 队列与「同刻一帧」承担：至多
 *       连接数条在飞，池背压面不放大）。</li>
 *   <li><b>槽位正交</b>：serial 一根线一场对话（授予与写线同一资源）；modbus 事务槽
 *       与线槽分离（帧可在无事务时派发、事务可在帧在飞时授予）——旧锁与旧单飞的
 *       真实语义就是分离的，合并反而改行为。</li>
 * </ul>
 *
 * <p><b>线程模型</b>：执行者线程只做排序、授予、派发与收尾，<b>永不执行阻塞 send 与
 * 设备代码</b>——阻塞 send 在 {@link ModbusIoPool} worker（既有约束保持）；授予回执
 * {@code granted} 完成在执行者/提交线程，{@code acquirePollingBounded} 的消费链经
 * {@code thenAcceptAsync} 卸载到 IO 池（调用方 SDK 定时线程零 park，事务体不在执行者
 * 线程上跑）。MDC 提交点捕获、worker 上恢复（沿用旧 dispatchIo 配方）。
 *
 * <p><b>deadline</b>（专用真时钟 {@link ModbusExecutorTimers}，到点体 µs 级：complete + 投递
 * 事件）：①帧 deadline = requestTimeout×6（与策略层事务硬超时同源
 * {@code boundedReadWaitMs}）——到点异常完成本帧 + 经 IO 池卸载强拆传输（RECOVERING，
 * 等 send 死亡后开下一帧，下一事务经 keepAlive 自愈）；这也是无锁裸帧路径的第一个
 * 挂死自愈（旧单飞形态下裸帧挂死=源永久瘫痪，无任何恢复）。②HELD hold deadline =
 * 幽灵收割阈值语义（默认 300s，可经测试缝缩短）——release 链整体丢失（持有线程被
 * master.send 永久吸收等）时强制收场，防持有者消亡锁死连接；不取 requestTimeout×6
 * 是为保持「非 strategy 直调 acquire 持有者」旧语义（旧世界其上限同样是 300s 收割）。
 * ③排队 wait deadline = 提交参数（acquire 超时 / 轮询预算），到点离队回 null（竞速
 * 输家无害：先授予者赢 {@code granted}，离队方 null，{@code release} 按不匹配 key
 * 拒绝——无双持有：授予只在执行者线程单点发生）。
 *
 * @author coffee
 */
final class ModbusConnectionExecutor {

    private final Log log = LogFactory.getLogger(ModbusConnectionExecutor.class);

    // ==================== 会话对象 ====================

    /** HELD 优先级：轮询入口=普通（预算耗尽弃轮），其余入口（acquire/executeWithLambda）=高。 */
    enum Priority { HIGH, NORMAL }

    /** HELD 会话自身生命周期（提交线程置 QUEUED，执行者线程迁移，离队竞速以先到者为准）。 */
    private enum HeldState { QUEUED, GRANTED, DONE }

    /**
     * 一场 HELD 对话（同步 acquire 的排队与持有载体）。不可变面在提交线程构造；可变面
     * （state/key/计时器）由执行者线程迁移（离队竞速在 admissionLock 内以 state 判定）。
     * granted 完成值：key=授予；null=满拒/超时/被挤出/离队（acquire 契约 null 语义）。
     */
    private static final class HeldConv {
        final long seq;
        final Priority priority;
        final ResourceOwner owner;
        /** 提交线程名（snapshot 归因用——旧 lockAcquireThread 的等价物）。 */
        final String submitterThread;
        /** 排队等待上限（毫秒）。 */
        final long waitMs;
        final CompletableFuture<String> granted = new CompletableFuture<>();
        HeldState state = HeldState.QUEUED;
        /** 授予后由执行者线程赋值（acquire 契约的锁标识，release 凭它收尾）。 */
        String key;
        long grantedAtMs;
        ScheduledFuture<?> waitTimer;
        ScheduledFuture<?> holdTimer;

        HeldConv(long seq, Priority priority, ResourceOwner owner, long waitMs) {
            this.seq = seq;
            this.priority = priority;
            this.owner = owner;
            this.submitterThread = Thread.currentThread().getName();
            this.waitMs = waitMs;
        }
    }

    /**
     * 一条帧对话（dispatchIo 的排队与派发载体）：请求工厂 + 错误文案 + 结果 CF + 提交时
     * 捕获的 MDC（IO 池 worker 上恢复，per-帧日志归因与旧 drainSends 逐字同配方）。
     * result 完成语义保持旧 dispatchIo 表面：传输异常=null；未知 RuntimeException=
     * 异常完成；旁池饱和=RejectedExecutionException 异常完成。
     */
    private static final class FrameConv {
        final long seq;
        final IoRequestFactory requestFactory;
        final String errorLabel;
        final Map<String, String> mdcContext;
        final CompletableFuture<ModbusMessage> result = new CompletableFuture<>();
        /** 帧 deadline 计时句柄（执行者线程/计时器线程迁移；null=未武装或已撤销）。 */
        ScheduledFuture<?> frameTimer;
        /** 收尾旗标（执行者线程置位）：deadline 到点体据此跳过已正常完成的帧（µs 竞速护栏）。 */
        volatile boolean done;

        FrameConv(long seq, IoRequestFactory requestFactory, String errorLabel, Map<String, String> mdcContext) {
            this.seq = seq;
            this.requestFactory = requestFactory;
            this.errorLabel = errorLabel;
            this.mdcContext = mdcContext;
        }
    }

    /** 单帧请求工厂（旧 ModbusSource 私有同名接口迁入——dispatchIo 的构造参数类型）。 */
    interface IoRequestFactory {
        ModbusRequest create() throws ModbusTransportException;
    }

    /** 阻塞 send 的事务包装缝（sendTraced：帧捕获+归因+发送；测试注入替身）。 */
    interface FrameRunner {
        ModbusMessage send(IoRequestFactory factory) throws ModbusTransportException;
    }

    /** 传输强拆缝（ModbusSource.forceRecoverTransport 提供；执行者帧 deadline 卸载调用）。 */
    interface RecoveryHook {
        void recover(String reason);
    }

    // ==================== 事件（执行者线程单一消费） ====================

    private abstract static class ExecEvent {
    }

    /** 唤醒令牌：可能有新帧可派发/新 HELD 可授予（多投无害，advance 幂等）。 */
    private static final class Wakeup extends ExecEvent {
    }

    /** HELD 手动收尾（release 已同步清槽，本事件只负责唤醒推进授予下一位）。 */
    private static final class WakeupNudge extends ExecEvent {
    }

    /** 排队等待到点（计时器）：仅收敛仍在队的对话——授予后到点为陈年事件，不得碰在持 HELD。 */
    private static final class WaitExpired extends ExecEvent {
        final HeldConv conv;

        WaitExpired(HeldConv conv) {
            this.conv = conv;
        }
    }

    /**
     * 调用方弃权（get 超时/中断/授予回执异常后投递——调用方确定未拿到 key）：仍在队则
     * 摘除回 null；已授予则强制收场（该 HELD 已无活主，不收场即泄漏到 hold deadline）。
     * 超时形态离队时按旧「Acquire timeout」契约留持有者真相行（logAsTimeout）。
     */
    private static final class Abandon extends ExecEvent {
        final HeldConv conv;
        final String reason;
        final boolean logAsTimeout;

        Abandon(HeldConv conv, String reason, boolean logAsTimeout) {
            this.conv = conv;
            this.reason = reason;
            this.logAsTimeout = logAsTimeout;
        }
    }

    /** HELD hold deadline 到点（release 链丢失防线）：强制收场并授予下一位。 */
    private static final class HeldExpired extends ExecEvent {
        final HeldConv conv;

        HeldExpired(HeldConv conv) {
            this.conv = conv;
        }
    }

    /** 帧 deadline 到点：异常完成本帧 + 卸载强拆 + 进入等待死亡确认（不开下一帧）。 */
    private static final class FrameDeadline extends ExecEvent {
        final FrameConv conv;

        FrameDeadline(FrameConv conv) {
            this.conv = conv;
        }
    }

    /** 在飞 send 完成（IO 池 worker 经 whenComplete 回投）：开下一帧（RECOVERING 解除）。 */
    private static final class FrameDone extends ExecEvent {
        final FrameConv conv;

        FrameDone(FrameConv conv) {
            this.conv = conv;
        }
    }

    // ==================== 状态 ====================

    private final String connectionId;
    private final int queueCapacity;
    private final long frameDeadlineMs;
    private final FrameRunner frameRunner;
    private final RecoveryHook recovery;

    /** 准入锁（提交线程小同步块）：容量判定、入队、挤出、release 快照判定、离队摘除。 */
    private final Object admissionLock = new Object();
    private final ArrayDeque<HeldConv> highQueue = new ArrayDeque<>();
    private final ArrayDeque<HeldConv> normalQueue = new ArrayDeque<>();
    /** 帧队列：无界（见类注刻意差异），FIFO，执行者线程单消费。 */
    private final ConcurrentLinkedQueue<FrameConv> frameQueue = new ConcurrentLinkedQueue<>();
    private final LinkedBlockingQueue<ExecEvent> events = new LinkedBlockingQueue<>();
    private final AtomicLong seqGen = new AtomicLong();
    /** 执行者线程命名计数（独立于会话 seq——线程重启不占用会话序号，观测面不混淆）。 */
    private final AtomicLong workerGen = new AtomicLong();

    /** 当前 HELD 持有者（执行者线程迁移；volatile 供快照/归因读面）。 */
    private volatile HeldConv currentHeld;
    /** 在飞帧（执行者线程迁移；volatile 供状态观测）。 */
    private volatile FrameConv frameInFlight;
    /** 帧 deadline 强拆后等待死亡确认（执行者线程独占；true 期间不开下一帧）。 */
    private boolean awaitingSendDeath;
    /** 空闲线程可退出标志（源销毁后防线程滞留；非拒绝态——见类注「无 CLOSED」）。 */
    private volatile boolean closed;
    private volatile Thread worker;

    /** HELD hold deadline（幽灵收割阈值语义，默认 300s；package-private 供测试缝缩短）。 */
    private long holdDeadlineMs;

    ModbusConnectionExecutor(String connectionId, int queueCapacity, long frameDeadlineMs,
            long holdDeadlineMs, FrameRunner frameRunner, RecoveryHook recovery) {
        this.connectionId = connectionId;
        this.queueCapacity = queueCapacity;
        this.frameDeadlineMs = frameDeadlineMs;
        this.holdDeadlineMs = holdDeadlineMs;
        this.frameRunner = frameRunner;
        this.recovery = recovery;
    }

    /** 红测注入口：缩短 HELD hold deadline（生产默认 300s，ModbusSource 测试缝转此）。 */
    void setHoldDeadlineMsForTest(long thresholdMs) {
        if (thresholdMs <= 0) {
            throw new IllegalArgumentException("holdDeadlineMs must be > 0, got: " + thresholdMs);
        }
        this.holdDeadlineMs = thresholdMs;
    }

    // ==================== 提交面（调用线程） ====================

    /**
     * 同步 acquire（旧 {@code acquire(timeout, unit, owner)} 契约）：返回锁标识，
     * null=队满拒绝/等待超时/被挤出/中断。快路径无竞争内联授予（调用线程，与旧
     * 「快速路径 currentKey==null 分支」同线程形态）；有竞争排队并阻塞等待授予回执。
     */
    String acquire(ResourceOwner owner, long waitMs) {
        HeldConv conv = new HeldConv(seqGen.incrementAndGet(), Priority.HIGH, owner, waitMs);
        if (!tryGrantNow(conv)) {
            try {
                admit(conv);
            } catch (RejectedExecutionException full) {
                log.warn("Max waiters exceeded, request rejected: c" + conv.seq + " (" + connectionId + ")");
                return null;
            }
        }
        try {
            return conv.granted.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 调用方放弃：投弃权事件关闭「授予竞速在 get 超时后完成」的泄漏窗口
            //（执行者侧：仍在队则摘除回 null 并留「Acquire timeout」真相行；已授予则强制
            // 收场——该 HELD 已无活主）
            post(new Abandon(conv, "acquire-caller-timeout", true));
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Wait interrupted: c" + conv.seq + ", connection: " + connectionId, e);
            post(new Abandon(conv, "acquire-caller-interrupted", false));
            return null;
        } catch (ExecutionException e) {
            // granted 设计上只以 key/null 完成（见授予/离队路径），异常完成即不变量
            // 破损——如实记错误并按「未授予」返回，不吞不换假成功
            log.error("Grant future completed exceptionally (invariant broken): c" + conv.seq
                    + ", connection: " + connectionId, e);
            post(new Abandon(conv, "grant-failed", false));
            return null;
        }
    }

    /**
     * 异步有界 acquire（旧 {@code acquirePollingBounded} 排队段契约）：返回授予回执
     * CF（key=授予；null=预算耗尽/满拒/被挤出）。完成点=执行者线程（授予）或计时器
     * 线程（到点）；调用方须自行把消费链卸载到非执行者线程（ModbusSource 经
     * {@code thenAcceptAsync(ioExecutor)}，事务体不在执行者线程跑）。
     *
     * @return null=队满且无可挤（满拒，调用方记账弃轮）；否则授予回执 CF
     */
    CompletableFuture<String> acquireAsync(ResourceOwner owner, long waitMs) {
        HeldConv conv = new HeldConv(seqGen.incrementAndGet(), Priority.NORMAL, owner, waitMs);
        if (!tryGrantNow(conv)) {
            try {
                admit(conv);
            } catch (RejectedExecutionException full) {
                log.warn("Max waiters exceeded, polling request rejected: c" + conv.seq + " (" + connectionId + ")");
                return null;
            }
        }
        return conv.granted;
    }

    /**
     * 提交一条帧对话：入无界帧队列 + 唤醒执行者。失败表面由 IO 任务体保持（传输异常
     * =null / RuntimeException=异常完成 / 旁池饱和=REE 异常完成），本方法只入队不派发。
     */
    CompletableFuture<ModbusMessage> submitFrame(IoRequestFactory requestFactory, String errorLabel,
            Map<String, String> mdcContext) {
        FrameConv conv = new FrameConv(seqGen.incrementAndGet(), requestFactory, errorLabel, mdcContext);
        frameQueue.add(conv);
        post(new Wakeup());
        return conv.result;
    }

    /**
     * HELD 手动收尾（旧 {@code release(key)} 契约，<b>同步语义保持</b>）：提交线程
     * admissionLock 内快照判定 + 状态清零（旧实现即「返回时状态已清」——快照观测面、
     * 紧随的快路径授予都依赖同步性，不做事件化降级）；不匹配立即 false（含「已过
     * hold deadline 被强制收场」的竞速，持有者意图已达成）。下一位授予由执行者线程
     * 经 WakeupNudge 推进（微秒级）。
     */
    boolean release(String releaseKey) {
        boolean matched;
        synchronized (admissionLock) {
            HeldConv held = currentHeld;
            matched = held != null && held.key != null && held.key.equals(releaseKey);
            if (matched) {
                cancel(held.holdTimer);
                held.holdTimer = null;
                held.state = HeldState.DONE;
                currentHeld = null;
            }
        }
        if (matched) {
            post(new WakeupNudge());
            return true;
        }
        log.warn("Invalid release key: " + releaseKey + " (" + connectionId + ")");
        return false;
    }

    /** 源销毁后的空闲退出标志（非拒绝态——销毁后帧/授予行为不变，见类注）。 */
    void markClosed() {
        closed = true;
        post(new Wakeup());
    }

    // ==================== 准入与授予 ====================

    /**
     * 无竞争快路径内联授予（提交线程，旧「快速路径 currentKey==null 分支」同线程
     * 形态）：无当前持有者且两队列全空才成立——有排队者时不插队（FIFO 公平不回退）。
     * 返回 false=需排队。
     */
    private boolean tryGrantNow(HeldConv conv) {
        synchronized (admissionLock) {
            if (currentHeld != null || !highQueue.isEmpty() || !normalQueue.isEmpty()) {
                return false;
            }
            grantHeld(conv);
            return true;
        }
    }

    /**
     * 准入本体（提交线程小同步块）：容量内入队；满时高优先级挤最新排队的普通对话
     * （被挤者 {@code granted} 以 null 完成——轮询方按弃轮记账、语义等价旧「队列满
     * 拒绝」）；全是高优先级则满拒。被挤收尾在锁外（其 waitTimer 取消涉及计时器句柄，
     * 与授予/离队同以 state 判定竞速）。
     *
     * @throws RejectedExecutionException 队满且无可挤（满拒——调用方按旧契约记 warn）
     */
    private void admit(HeldConv conv) {
        HeldConv evicted = null;
        synchronized (admissionLock) {
            if (highQueue.size() + normalQueue.size() >= queueCapacity) {
                if (conv.priority == Priority.HIGH) {
                    evicted = normalQueue.pollLast();
                    if (evicted == null) {
                        throw new RejectedExecutionException(
                                "held queue full (" + queueCapacity + ") with no normal waiter to evict: " + connectionId);
                    }
                } else {
                    throw new RejectedExecutionException("held queue full (" + queueCapacity + "): " + connectionId);
                }
            }
            if (conv.priority == Priority.HIGH) {
                highQueue.addLast(conv);
            } else {
                normalQueue.addLast(conv);
            }
        }
        if (evicted != null) {
            evict(evicted);
        }
        scheduleWaitTimer(conv);
        post(new Wakeup());
    }

    /**
     * 被挤对话收尾：state 置 DONE（执行者授予/离队路径见非 QUEUED 即跳过——竞速安全）、
     * waitTimer 取消、{@code granted} 以 null 完成。null 在轮询侧即弃轮语义
     * （ModbusSource 记账），与旧「Max waiters exceeded → null」等价。
     */
    private void evict(HeldConv victim) {
        victim.state = HeldState.DONE;
        cancel(victim.waitTimer);
        victim.granted.complete(null);
        log.warn("Held conversation c" + victim.seq + " evicted by high-priority arrival ("
                + connectionId + "), polling owner will record a skip");
    }

    /** 排队等待计时器：到点投递到点事件（摘除在执行者线程单点收敛；仅 QUEUED 态生效）。 */
    private void scheduleWaitTimer(final HeldConv conv) {
        try {
            conv.waitTimer = ModbusExecutorTimers.fireAfter(() -> post(new WaitExpired(conv)), conv.waitMs);
        } catch (RuntimeException scheduleEx) {
            // 计时提交被拒（调度器饱和/停机）：无计时器的排队对话失去超时收敛，立即回
            // null 离队（fail-fast，杜绝不可终结对话）；state 竞速由离队路径判定兜住
            removeFromQueues(conv);
            conv.state = HeldState.DONE;
            conv.granted.complete(null);
            log.warn("Wait timer submission failed, conversation c" + conv.seq + " failed fast: " + scheduleEx.getMessage());
        }
    }

    /** 授予本体（执行者线程或提交线程快路径）：置持有者 + key + 授予回执 + hold 计时。 */
    private void grantHeld(HeldConv conv) {
        cancel(conv.waitTimer);
        conv.waitTimer = null;
        conv.state = HeldState.GRANTED;
        conv.key = "c" + conv.seq;
        conv.grantedAtMs = System.currentTimeMillis();
        currentHeld = conv;
        conv.granted.complete(conv.key);
        if (holdDeadlineMs > 0) {
            try {
                conv.holdTimer = ModbusExecutorTimers.fireAfter(() -> post(new HeldExpired(conv)), holdDeadlineMs);
            } catch (RuntimeException scheduleEx) {
                // hold 计时提交被拒：本持有者失去强制收场防线（与旧世界 schedule 失败
                // 同形态——release 链依赖持有者自律），warn 记账不阻塞授予
                log.warn("Hold-deadline timer submission failed for c" + conv.seq
                        + ", hold enforcement degraded: " + scheduleEx.getMessage());
            }
        }
    }

    // ==================== 执行者线程 ====================

    private void ensureThreadStarted() {
        synchronized (this) {
            if (worker != null) {
                return;
            }
            Thread thread = new Thread(this::loop,
                    "ecat-modbus-exec[" + connectionId + "]-" + workerGen.incrementAndGet());
            thread.setDaemon(true);
            worker = thread;
            thread.start();
        }
    }

    /** 事件投递（任何线程）：入邮箱并确保执行者线程活着（空闲退出后按需重启）。 */
    private void post(ExecEvent event) {
        events.add(event);
        ensureThreadStarted();
    }

    private void loop() {
        for (;;) {
            ExecEvent event;
            try {
                event = events.take();
            } catch (InterruptedException e) {
                // 本线程无中断停机协议（daemon；关闭=markClosed 事件路径）——杂散中断
                // 不静默吞：恢复状态并退出（下次 post 按需重启，语义无损）
                Thread.currentThread().interrupt();
                synchronized (this) {
                    if (worker == Thread.currentThread()) {
                        worker = null;
                    }
                }
                return;
            }
            handle(event);
            advance();
            if (closed && idleForExit()) {
                synchronized (this) {
                    if (worker == Thread.currentThread()) {
                        worker = null;
                    }
                }
                return;
            }
        }
    }

    private boolean idleForExit() {
        synchronized (admissionLock) {
            return currentHeld == null && frameInFlight == null && !awaitingSendDeath
                    && highQueue.isEmpty() && normalQueue.isEmpty() && frameQueue.isEmpty();
        }
    }

    private void handle(ExecEvent event) {
        if (event instanceof Wakeup || event instanceof WakeupNudge) {
            // advance() 统一推进（WakeupNudge=同步 release 后的下一位授予推进）
        } else if (event instanceof WaitExpired) {
            handleWaitExpired(((WaitExpired) event).conv);
        } else if (event instanceof Abandon) {
            handleAbandon((Abandon) event);
        } else if (event instanceof HeldExpired) {
            handleHeldExpired(((HeldExpired) event).conv);
        } else if (event instanceof FrameDeadline) {
            handleFrameDeadline(((FrameDeadline) event).conv);
        } else if (event instanceof FrameDone) {
            handleFrameDone(((FrameDone) event).conv);
        }
    }

    /**
     * 推进（幂等，每个事件后调用）：授予无主 HELD + 派发可派帧。
     * 授予判定与 {@link #tryGrantNow} 同锁（admissionLock）——快路径授予（提交线程）
     * 与队列授予（执行者线程）双写方，无锁则双授予竞态。
     */
    private void advance() {
        grantNextHeld();
        dispatchFrames();
    }

    private void grantNextHeld() {
        for (;;) {
            HeldConv conv;
            synchronized (admissionLock) {
                if (currentHeld != null) {
                    return;
                }
                conv = pollHeadHeld();
                if (conv == null) {
                    return;
                }
                if (conv.state != HeldState.QUEUED) {
                    continue; // 已离队/被挤出的竞速输家：跳过取下一位（锁内继续，不释放判定权）
                }
                grantHeld(conv); // 内含撤等待计时器（到点竞速由 handleWaitExpired 的 QUEUED 判定兜住）
                return;
            }
        }
    }

    private HeldConv pollHeadHeld() {
        synchronized (admissionLock) {
            HeldConv head = highQueue.pollFirst();
            return head != null ? head : normalQueue.pollFirst();
        }
    }

    /** 取消排队对话（从两队列摘除；state 判定由调用方负责）。 */
    private void removeFromQueues(HeldConv conv) {
        synchronized (admissionLock) {
            highQueue.remove(conv);
            normalQueue.remove(conv);
        }
    }

    /** 排队等待到点：仅收敛<b>仍在队</b>的对话（摘除回 null；超时行带持有者真相——完整
     * 等待预算内零 release 只能是持有方卡顿，快照让偶发卡顿现场可回溯，LOCKRCA 建议A
     * 观测性原样保持）。已授予后到点=陈年事件（授予时已撤计时器，此处只兜竞速漏网），
     * 绝不碰在持 HELD——合法持有不受等待超时伤害。
     */
    private void handleWaitExpired(HeldConv conv) {
        if (conv.state != HeldState.QUEUED) {
            return;
        }
        removeFromQueues(conv);
        cancel(conv.waitTimer);
        conv.state = HeldState.DONE;
        conv.granted.complete(null);
        logAcquireTimeout(conv);
    }

    /**
     * 调用方弃权（get 超时/中断/授予回执异常——调用方确定未拿到 key）：仍在队→摘除回
     * null（超时形态按旧契约留「Acquire timeout」持有者真相行）；已授予（授予与弃权
     * 竞速）→强制收场（该 HELD 已无活主，不收场即泄漏到 hold deadline）。
     */
    private void handleAbandon(Abandon event) {
        HeldConv conv = event.conv;
        if (conv.state == HeldState.QUEUED) {
            removeFromQueues(conv);
            cancel(conv.waitTimer);
            conv.state = HeldState.DONE;
            conv.granted.complete(null);
            if (event.logAsTimeout) {
                logAcquireTimeout(conv);
            }
            return;
        }
        synchronized (admissionLock) {
            if (currentHeld == conv) {
                log.warn("Held conversation c" + conv.seq + " force-ended (" + event.reason
                        + " after grant, holder never obtained the key): " + connectionId);
                cancel(holdTimerOf(conv));
                conv.state = HeldState.DONE;
                currentHeld = null;
            }
        }
    }

    /** 「Acquire timeout」持有者真相行（旧契约形态逐字保持；离队单点收敛恰好一条）。 */
    private void logAcquireTimeout(HeldConv conv) {
        log.error("Acquire timeout: c" + conv.seq + ", connection: " + connectionId
                + ", " + lockHolderSnapshot() + ", waiters=" + getWaitingCount());
    }

    /**
     * HELD hold deadline 到点（release 链丢失防线，旧幽灵锁收割的执行者形态）：强制
     * 收场并授予下一位。沿用旧收割日志标签（同一故障模式：release 永久缺失），机制
     * 已从「扫描 currentKey 时间戳」变为「单线程状态机到期强制收场」。
     */
    private void handleHeldExpired(HeldConv conv) {
        synchronized (admissionLock) {
            if (currentHeld != conv) {
                return; // 陈年计时器（已 release/已收场/快路径授予的其他会话）
            }
            long heldMs = System.currentTimeMillis() - conv.grantedAtMs;
            log.error("[MODBUS-GHOST-LOCK-REAPED] trigger=hold-deadline, HELD 持有 {}ms 超阈值 {}ms 未 release"
                            + "（release 链丢失，强制收场防连接锁死）: key={}, owner={}, connection: {}",
                    heldMs, holdDeadlineMs, conv.key, ownerIdentity(conv.owner), connectionId);
            cancel(conv.holdTimer);
            conv.holdTimer = null;
            conv.state = HeldState.DONE;
            currentHeld = null;
        }
    }

    // ==================== 帧派发（IO 池 worker 执行阻塞 send——保持不变） ====================

    /**
     * 派发循环：同刻至多一帧在飞（线槽互斥）；RECOVERING（等垂死 send 确认）期间暂停。
     * 旁池饱和（REE）→ 本帧立即异常完成（过期即弃，与旧 failAllPendingSends 的逐帧
     * 语义等价），清线槽继续派发下一帧（仍饱和则逐帧快速失败，源不滞留）。
     */
    private void dispatchFrames() {
        while (frameInFlight == null && !awaitingSendDeath) {
            FrameConv conv = frameQueue.poll();
            if (conv == null) {
                return;
            }
            dispatchFrame(conv);
        }
    }

    private void dispatchFrame(final FrameConv conv) {
        frameInFlight = conv;
        scheduleFrameTimer(conv);
        try {
            ModbusIoPool.executor().execute(() -> runFrame(conv));
        } catch (RejectedExecutionException poolSaturated) {
            cancel(frameTimerOf(conv));
            frameInFlight = null;
            conv.result.completeExceptionally(poolSaturated);
            log.warn("Modbus IO pool saturated, frame c" + conv.seq + " failed fast (过期即弃): "
                    + conv.errorLabel);
            return; // 调用循环继续派发下一帧
        }
        // 完成回投（IO 池 worker 线程）：执行者据此开下一帧
        conv.result.whenComplete((res, ex) -> post(new FrameDone(conv)));
    }

    /**
     * 单帧执行体（IO 池 worker，旧 drainSends 逐帧体逐字同配方）：MDC 提交时捕获、
     * worker 上恢复，CF 的同步依赖（解析/属性更新链）在本帧自己的 MDC 下执行。
     */
    private void runFrame(FrameConv conv) {
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        try {
            if (conv.mdcContext != null) {
                MDC.setContextMap(conv.mdcContext);
            }
            conv.result.complete(frameRunner.send(conv.requestFactory));
        } catch (ModbusTransportException e) {
            log.error(conv.errorLabel, e);
            conv.result.complete(null);
        } catch (RuntimeException e) {
            // 未知异常如实异常完成（严格模式：不吞不换 null 掩盖）
            conv.result.completeExceptionally(e);
        } finally {
            if (conv.mdcContext != null) {
                if (previousMdc != null) {
                    MDC.setContextMap(previousMdc);
                } else {
                    MDC.clear();
                }
            }
        }
    }

    /** 帧 deadline 计时（requestTimeout×6 语义，策略层事务硬超时同源）：到点体 µs 级。 */
    private void scheduleFrameTimer(final FrameConv conv) {
        if (frameDeadlineMs <= 0) {
            return; // 与策略层同守卫：非法/未配 deadline 不武装（生产 requestTimeoutMs 恒正）
        }
        try {
            ScheduledFuture<?> timer = ModbusExecutorTimers.fireAfter(() -> {
                if (conv.done) {
                    return; // 已正常收尾（µs 竞速漏网）：不误杀健康帧、不误强拆
                }
                // 到点体只做 complete + 卸载强拆 + 投事件（µs 级）。强拆投递放在计时器体
                // 而非 FrameDeadline 处理点：FrameDone（send 恰在 deadline 边界返回）与
                // FrameDeadline 的事件序竞速不会丢强拆——挂死强证据下恢复必达且恰好一次
                conv.result.completeExceptionally(new TimeoutException(
                        "Frame hard timeout after " + frameDeadlineMs + " ms"));
                submitRecoveryOffload(conv);
                post(new FrameDeadline(conv));
            }, frameDeadlineMs);
            conv.frameTimer = timer;
        } catch (RuntimeException scheduleEx) {
            // 计时提交被拒：帧失去 deadline 防线（旧世界该形态=裸帧挂死无兜底），warn 记账
            conv.frameTimer = null;
            log.warn("Frame deadline timer submission failed for c" + conv.seq
                    + ", deadline enforcement degraded: " + scheduleEx.getMessage());
        }
    }

    /** 强拆传输的统一卸载（IO 池执行，destroy/init 可阻塞动作不占计时器/执行者线程）。 */
    private void submitRecoveryOffload(FrameConv conv) {
        try {
            ModbusIoPool.executor().execute(() -> {
                try {
                    recovery.recover("executor-frame-hard-timeout");
                } catch (Exception recoverEx) {
                    log.warn("Frame deadline transport recovery failed for c" + conv.seq + ": "
                            + recoverEx.getMessage());
                }
            });
        } catch (RejectedExecutionException submitEx) {
            log.warn("Frame deadline recovery submission rejected for c" + conv.seq + " (io pool rejected?): "
                    + submitEx.getMessage());
        }
    }

    /**
     * 帧 deadline 事件：进入等待死亡确认（不开下一帧：垂死 send 可能仍有重发在途，防线上
     * 碰撞）；强拆已由计时器体卸载。send 死亡确认（FrameDone）后下一帧经
     * keepAlive/每事务重开自愈。结果 CF 已由计时器线程异常完成——调用方不等恢复动作
     * （与策略层超时路径同形）。
     */
    private void handleFrameDeadline(FrameConv conv) {
        if (frameInFlight != conv) {
            return; // 陈年计时器（已正常完成，死亡确认已先到）
        }
        log.error("[MODBUS-FRAME-DEADLINE] 帧硬超时 {}ms（阻塞 send 挂死强证据），已卸载强拆传输，"
                + "等待在飞 send 死亡后开下一帧: c{} ({})", frameDeadlineMs, conv.seq, connectionId);
        awaitingSendDeath = true;
    }

    private void handleFrameDone(FrameConv conv) {
        conv.done = true;
        cancel(frameTimerOf(conv));
        if (frameInFlight != conv) {
            return; // 陈年回投（deadline 已收场转交/池拒绝路径已清）
        }
        frameInFlight = null;
        awaitingSendDeath = false;
    }

    // ==================== 观测面（快照/计数） ====================

    /** 当前 HELD 排队数（旧 getWaitingCount 语义：已受理未授予的等待者数）。 */
    int getWaitingCount() {
        synchronized (admissionLock) {
            return highQueue.size() + normalQueue.size();
        }
    }

    /** 当前持有 owner（帧归因权威，旧 lockAcquireOwner 读面；null=无主/已清）。 */
    ResourceOwner currentOwner() {
        HeldConv held = currentHeld;
        return held != null ? held.owner : null;
    }

    /**
     * 持有者真相快照（旧 lockHolderSnapshot 形态逐字保持，消费方=策略层取锁失败日志
     * 与弃轮限频日志）：{@code currentHeld} 单写者（执行者线程）+ 授予后字段稳定，
     * volatile 读无撕裂。无持有者返回 {@code lock currently free}——「等待期间卡顿已
     * 自愈释放」的直接证据形态不变。
     */
    String lockHolderSnapshot() {
        HeldConv held = currentHeld;
        if (held == null) {
            return "lock currently free";
        }
        long heldMs = held.grantedAtMs > 0 ? System.currentTimeMillis() - held.grantedAtMs : -1;
        return "lock currently held by: " + held.key
                + " (acquired at " + held.grantedAtMs + " by thread " + held.submitterThread
                + ", held " + heldMs + "ms, owner=" + ownerIdentity(held.owner) + ")";
    }

    /** 当前在飞帧序号（观测/测试缝；-1=无在飞帧）。 */
    long frameInFlightSeq() {
        FrameConv frame = frameInFlight;
        return frame != null ? frame.seq : -1L;
    }

    // ==================== 小工具 ====================

    private static String ownerIdentity(ResourceOwner owner) {
        return owner == null ? "none" : owner.ownerKey();
    }

    private static ScheduledFuture<?> holdTimerOf(HeldConv conv) {
        ScheduledFuture<?> timer = conv.holdTimer;
        conv.holdTimer = null;
        return timer;
    }

    private static ScheduledFuture<?> frameTimerOf(FrameConv conv) {
        ScheduledFuture<?> timer = conv.frameTimer;
        conv.frameTimer = null;
        return timer;
    }

    private static void cancel(ScheduledFuture<?> timer) {
        if (timer != null) {
            timer.cancel(false);
        }
    }
}
