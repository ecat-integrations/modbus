package com.ecat.integration.ModbusIntegration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.ecat.core.EcatCore;
import com.ecat.core.Task.NamedThreadFactory;
import com.ecat.core.Task.TaskManager;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import com.ecat.core.Utils.Mdc.MdcExecutorService;

import lombok.Value;

/**
 * Modbus source 事务车道解析（IO 线程收敛 P2）：每个连接的 Modbus 事务不再各自持有
 * dedicated 单线程 executor（live core 实测 57 源 = 57 条 modbus-source-* 常驻线程），
 * 改为提交到共享调度引擎的「连接车道」——同连接事务严格串行（防共享连接/RS485 总线帧碰撞，
 * 与旧单线程池语义 1:1）、异连接并行、MDC 在引擎提交时捕获传播（与门面任务同权）。
 *
 * <p>车道键：{@code modbus-source:{连接标识}}（TCP 为 ip:port，RTU 为串口名）——分道粒度是
 * 「资源坐标」而非提交方集成坐标（多设备/多集成共享一条连接时必须同道），故走
 * {@link TaskManager#executorFor(String)} 显式键通道。
 *
 * <p>解析顺序（{@link #resolve(String)}）：
 * <ol>
 *   <li>{@link #bind(TaskManager) 显式绑定}——无 core 上下文的单测注入引擎实例的边界；
 *       生产代码不得依赖此层。</li>
 *   <li>运行中的 ECAT 平台（{@code EcatCore.getInstance()} 非空）→
 *       {@link TaskManager#executorFor(String)} 引擎车道视图（生产路径）。</li>
 *   <li>本地兜底 per-source 单线程 executor（{@code modbus-source-{conn}} 命名、非 daemon，
 *       与旧实现一致）——无 core 上下文的单测/独立运行。此为显式测试边界，非生产兜底：
 *       生产中集成由 EcatCore 加载，core 实例必先于任何设备代码就绪。</li>
 * </ol>
 *
 * <p>所有权契约（{@link Lane#engineOwned}）：引擎车道视图是共享调度引擎的窗口，无独立生命周期
 * （shutdown() 显式抛 UnsupportedOperationException）——source 销毁时不得停掉它，车道随任务
 * 自然排空（在途事务被引擎 30s 硬超时界住，单事务最坏 ~12s）；只有本地兜底 executor 归
 * source 所有，销毁时须 shutdown 释放线程。「源已死」语义由 ModbusSource 自持 destroyed 标志
 * 表达，禁止借用 executor.isShutdown()（引擎视图恒随引擎、与单源生死无关）。
 *
 * @author coffee
 */
public final class ModbusSourceLanes {

    private static final Log log = LogFactory.getLogger(ModbusSourceLanes.class);

    /** 车道键前缀（资源域前缀 + 连接标识，与引擎显式键命名约定一致）。 */
    public static final String LANE_KEY_PREFIX = "modbus-source:";

    /** 测试显式注入的引擎（bind/unbind）；null = 未注入，走默认解析。 */
    private static volatile TaskManager bound;

    private ModbusSourceLanes() {
    }

    /**
     * 显式注入调度引擎（测试边界）：注入后 {@link #resolve(String)} 的引擎层恒返回该实例的
     * 车道视图，便于无 core 上下文的单测确定性地断言「事务确实经由引擎车道」。
     *
     * @param taskManager 测试自有的引擎实例（生命周期由测试管理，本类不关停）
     */
    public static void bind(TaskManager taskManager) {
        if (taskManager == null) {
            throw new IllegalArgumentException("bind(null) 不允许——解除绑定用 unbind()");
        }
        bound = taskManager;
    }

    /** 解除显式绑定，恢复默认解析（core 引擎 → 本地兜底）。 */
    public static void unbind() {
        bound = null;
    }

    /**
     * 解析连接的事务车道（解析顺序见类 Javadoc）。
     *
     * @param connectionIdentity 连接标识（TCP ip:port / RTU 串口名），兼作兜底 executor 线程名
     * @return 车道（含所有权标记，销毁语义见 {@link Lane#engineOwned}）
     */
    public static Lane resolve(String connectionIdentity) {
        TaskManager explicit = bound;
        if (explicit != null) {
            return Lane.engineOwned(explicit.executorFor(LANE_KEY_PREFIX + connectionIdentity));
        }
        EcatCore core = EcatCore.getInstance();
        if (core != null) {
            return Lane.engineOwned(core.getTaskManager().executorFor(LANE_KEY_PREFIX + connectionIdentity));
        }
        return localFallback(connectionIdentity);
    }

    /**
     * 本地兜底：per-source 单线程 executor，与迁移前实现一致（命名 + 非 daemon + MDC 包装）。
     * 同源串行语义由单线程保证；线程归 source 所有，销毁时 shutdown。
     */
    private static Lane localFallback(String connectionIdentity) {
        log.info("无 ECAT core 上下文（单测/独立运行），使用本地事务线程 modbus-source-" + connectionIdentity);
        return Lane.locallyOwned(MdcExecutorService.wrap(Executors.newSingleThreadExecutor(
                new NamedThreadFactory("modbus-source-" + connectionIdentity, false))));
    }

    /**
     * 事务车道 + 所有权标记：engineOwned=true 为共享引擎视图（不建池不建线程，销毁源时不得
     * shutdown）；false 为本地兜底单线程（归源所有，销毁时须 shutdown 释放线程）。
     */
    @Value
    public static class Lane {
        ExecutorService executor;
        boolean engineOwned;

        static Lane engineOwned(ExecutorService executor) {
            return new Lane(executor, true);
        }

        static Lane locallyOwned(ExecutorService executor) {
            return new Lane(executor, false);
        }
    }
}
