package com.ecat.integration.ModbusIntegration;

import org.junit.Test;

import java.util.concurrent.ExecutorService;

import static org.junit.Assert.*;

/**
 * 事务车道命名回归测试（无 core 上下文 = 本地兜底路径）：非 delegateMode 的 ModbusSource
 * 在本地兜底下必须创建具名单线程执行器（modbus-source-{laneName}-N）。原实现
 * Executors.newSingleThreadExecutor() 产生无名 pool-N-thread-1，58 个连接即 58 个无法归因的
 * 池线程（B6 线程普查 top 创建者）。P2 后生产路径换共享引擎车道（线程收敛 57→0，见
 * ModbusSourceLanes 与 ModbusSourceEngineLaneTest）；本测试锁定兜底路径的命名不回退。
 * delegateMode 保持不创建执行器（委托共享 source）。
 */
public class ModbusSourceLaneNamingTest {

    @Test
    public void nonDelegateSourceHasNamedLaneExecutor() throws Exception {
        ModbusTcpInfo info = new ModbusTcpInfo("192.0.2.1", 502, 1);
        // skipOpen=true：不建 master 不碰网络，只验证执行器命名
        ModbusSource source = new ModbusSource(info, 1, 100, true, false, "192.0.2.1:502");

        ExecutorService lane = source.getLaneExecutor();
        assertNotNull(lane);
        final String[] seenName = new String[1];
        lane.submit(() -> seenName[0] = Thread.currentThread().getName()).get();
        assertTrue("lane thread should be named modbus-source-192.0.2.1:502-*, got: " + seenName[0],
                seenName[0].startsWith("modbus-source-192.0.2.1:502-"));
        assertFalse("lane thread must stay non-daemon (same as Executors default factory)",
                Thread.getAllStackTraces().keySet().stream()
                        .filter(t -> t.getName().startsWith("modbus-source-192.0.2.1:502-"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("lane thread not alive"))
                        .isDaemon());
        // 只验证命名（destroyResources 走 destroyMaster(null) NPE 是既知非本测路径，
        // 生产中 destroyResources 只作用于带 master 的 source）
        lane.shutdown();
        assertTrue(lane.isShutdown());
    }

    @Test
    public void delegateSourceCreatesNoExecutor() throws Exception {
        ModbusTcpInfo info = new ModbusTcpInfo("192.0.2.2", 502, 2);
        ModbusSource source = new ModbusSource(info, 1, 100, true, true, "ignored");
        assertNull(source.getLaneExecutor());
    }
}
