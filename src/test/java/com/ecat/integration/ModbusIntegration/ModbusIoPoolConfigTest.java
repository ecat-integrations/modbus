package com.ecat.integration.ModbusIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.ecat.core.Integration.IntegrationManager;
import com.ecat.core.Integration.IntegrationRegistry;
import com.ecat.core.Utils.TestTools;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersRequest;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersResponse;

/**
 * R3 期 4（15 号 §6.4 D4）：io_pool_size 配置项契约——
 * <ul>
 *   <li>①ConfigDefinition 声明 io_pool_size（Integer，1..64，默认 Const.DEFAULT_IO_POOL_SIZE=16，
 *       与 onInit 读取 fallback 双引用同一常量——F-26 同源纪律）；</li>
 *   <li>②onInit 合法配置读入并按容量建池（行为证明：容量 2 → 恰 2 个 ecat-modbus-io 线程，
 *       超量帧入池内排队吸收不失败）；非法配置回退默认值（与 max_waiters 同形态）；</li>
 *   <li>③onRelease 关停旁池（源销毁之后，graceful）。</li>
 * </ul>
 * 配置注入路径与 ModbusIntegrationTest 同型：mock IntegrationManager.loadConfig。
 */
public class ModbusIoPoolConfigTest {

    private ModbusIntegration integration;
    private IntegrationManager integrationManager;
    private ModbusMaster master;

    @Before
    public void setUp() throws Exception {
        integration = new ModbusIntegration();
        integrationManager = mock(IntegrationManager.class);
        TestTools.setPrivateField(integration, "integrationManager", integrationManager);
        TestTools.setPrivateField(integration, "integrationRegistry", mock(IntegrationRegistry.class));
        master = mock(ModbusMaster.class);
        ModbusIoPool.resetForTest(); // 隔离：每测从干净池状态开始（终端态测试也先复位）
    }

    @After
    public void tearDown() {
        ModbusIoPool.resetForTest(); // 复位（非 shutdown）：终端态留给本类显式断言，后续类可再惰性建池
        // onRelease 用例经 onReleaseImpl 把 SDK timers 置终端态（不自动复活）——必须复位，
        // 否则 NTFS 字典类序下 terminated 终态泄漏给后续全部消费 timers 真池的测试类
        // （对齐 ModbusIntegrationTest:99 惯例；bug-record-20260831-151758）
        com.ecat.integration.ModbusIntegration.Sdk.ModbusSdkTimers.resetForTest();
        Mockito.validateMockitoUsage();
    }

    private void initWithConfig(Map<String, Object> config) {
        when(integrationManager.loadConfig(anyString())).thenReturn(config);
        integration.onInit();
    }

    /** ①配置声明：合法范围 1..64；0/65 拒绝。 */
    @Test
    public void ioPoolSizeIsDeclaredWithRangeValidation() {
        assertTrue("io_pool_size=1 应合法",
                integration.getConfigDefinition().validateConfig(configOf("io_pool_size", 1)));
        assertTrue("io_pool_size=64 应合法",
                integration.getConfigDefinition().validateConfig(configOf("io_pool_size", 64)));
        assertFalse("io_pool_size=0 应非法（下界 1）",
                integration.getConfigDefinition().validateConfig(configOf("io_pool_size", 0)));
        assertFalse("io_pool_size=65 应非法（上界 64）",
                integration.getConfigDefinition().validateConfig(configOf("io_pool_size", 65)));
    }

    /** ②合法配置读入 + 容量行为证明：io_pool_size=2 → 恰 2 个 ecat-modbus-io 线程并发在飞；
     * 后续源的帧入池内排队（突发吸收，4×N 容量），释放后完成不失败。 */
    @Test
    public void onInitHonorsConfiguredPoolSize() throws Exception {
        initWithConfig(configOf("io_pool_size", 2));
        assertEquals("onInit 应读入配置容量", Integer.valueOf(2), integration.ioPoolSize);

        // 两源（=两条连接）并发在飞：占满 2 线程
        ModbusSource sourceA = new ModbusSource(new ModbusTcpInfo("127.0.0.1", 1502, 1), 1, 2000, true, false);
        ModbusSource sourceB = new ModbusSource(new ModbusTcpInfo("127.0.0.1", 1503, 1), 1, 2000, true, false);
        TestTools.setPrivateField(sourceA, "modbusMaster", master);
        TestTools.setPrivateField(sourceB, "modbusMaster", master);
        CountDownLatch twoInFlight = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(master.send(any(ReadHoldingRegistersRequest.class))).thenAnswer(inv -> {
            twoInFlight.countDown();
            release.await();
            return mock(ReadHoldingRegistersResponse.class);
        });

        sourceA.readHoldingRegisters(0, 1);
        sourceB.readHoldingRegisters(2, 1);
        assertTrue("两帧应并发在飞（容量 2 占满）", twoInFlight.await(5, TimeUnit.SECONDS));

        // 配置容量的线程级证明：恰 2 个 ecat-modbus-io-* 线程
        long poolThreads = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("ecat-modbus-io-")).count();
        assertTrue("容量 2 应恰建 2 个旁池线程，实际: " + poolThreads, poolThreads == 2);

        // 突发吸收：第 3 源的帧排队不失败，释放后完成
        ModbusSource sourceC = new ModbusSource(new ModbusTcpInfo("127.0.0.1", 1504, 1), 1, 2000, true, false);
        TestTools.setPrivateField(sourceC, "modbusMaster", master);
        CompletableFuture<ReadHoldingRegistersResponse> burst = sourceC.readHoldingRegisters(4, 1);
        assertTrue("排队帧不得被拒绝失败（突发吸收）", !burst.isCompletedExceptionally());
        release.countDown();
        assertTrue("释放后排队帧完成", burst.get(5, TimeUnit.SECONDS) != null);
    }

    /** ②非法配置回退默认（与 max_waiters 同形态）：io_pool_size=0 → DEFAULT_IO_POOL_SIZE。 */
    @Test
    public void onInitFallsBackToDefaultOnInvalidConfig() {
        initWithConfig(configOf("io_pool_size", 0));
        assertEquals("非法配置应回退 Const.DEFAULT_IO_POOL_SIZE（F-26 同源）",
                Const.DEFAULT_IO_POOL_SIZE, integration.ioPoolSize);
    }

    /**
     * ③onRelease 关停旁池：关停后 onInit 期池引用 isShutdown（源先销毁、池后关停的生命周期序）。
     * R-F 终端态契约：关停后取用 executor() 必须显式 REE（不静默懒重建）；显式 initialize
     * （生产 owner 重初始化路径）可重开窗口。
     */
    @Test
    public void onReleaseShutsDownIoPool_thenExecutorRejectedUntilExplicitReinit() {
        initWithConfig(new HashMap<>());
        ExecutorService poolAtInit = ModbusIoPool.executor();
        assertNotNull(poolAtInit);
        assertFalse("onInit 后旁池应存活", poolAtInit.isShutdown());

        integration.onRelease();
        assertTrue("onRelease 必须关停旁池（graceful shutdown）", poolAtInit.isShutdown());
        try {
            ModbusIoPool.executor();
            fail("终端态后取用必须 RejectedExecutionException（R-F：不懒重建，对齐三域）");
        } catch (RejectedExecutionException expected) { }

        // 显式重初始化（onInit→initialize 生产 owner 责任）重开窗口
        initWithConfig(configOf("io_pool_size", 2));
        assertFalse("显式 initialize 后旁池恢复可取用", ModbusIoPool.executor().isShutdown());
    }

    private static Map<String, Object> configOf(String key, Object value) {
        Map<String, Object> config = new HashMap<>();
        config.put(key, value);
        return config;
    }
}
