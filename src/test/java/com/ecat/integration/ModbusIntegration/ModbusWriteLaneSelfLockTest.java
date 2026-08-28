package com.ecat.integration.ModbusIntegration;

import com.ecat.core.Utils.TestTools;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.msg.ModbusRequest;
import com.serotonin.modbus4j.msg.WriteRegisterResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 写命令自锁形态回归（bug-record-20260825-101000，M8；R3 期 4 后语义更新）。
 *
 * <p>被测契约：写命令任务体在 {@code modbus-source:{conn}} 调用方车道上发起写并 join 写事务
 * future（ioBody 形态），join 必须立即完成——不得自等自死锁（M8 原缺陷：发帧任务排回同一
 * lane 队列，任务体同步等待同 lane 后继任务，12s 硬超时强拆才放行）。
 *
 * <p>R3 期 4 后（15 号 §6.4 写迁移）：写 send 派发到 IO 旁池（旁池≠lane），车道线程的
 * join 是跨执行器 join——无同 lane 重入，自锁形态结构性不可表达；本测试锁定该不变量：
 * 任何「写回退到 lane/调用线程直发」或「写提交回发起 lane」的回归都会在此红。
 *
 * <p>测试形态与生产同构：lane 任务体内发起写并 join。修复前本测试红（join 5s 超时）；
 * 旁池形态毫秒级通过。
 */
public class ModbusWriteLaneSelfLockTest {

    /** 调用方车道替身（同键串行语义=单线程执行器；W7 引擎车道退役后的等价 fixture）。 */
    private final ExecutorService lane = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "modbus-lane-test");
        t.setDaemon(true);
        return t;
    });
    private ModbusMaster master;

    @Before
    public void setUp() {
        master = mock(ModbusMaster.class);
    }

    @After
    public void tearDown() {
        lane.shutdownNow();
    }

    private ModbusSource newSource() throws Exception {
        ModbusTcpInfo info = new ModbusTcpInfo("127.0.0.1", 1507, 1);
        ModbusSource source = new ModbusSource(info, 1, 2000, true, false);
        TestTools.setPrivateField(source, "modbusMaster", master);
        return source;
    }

    /**
     * 写命令在自有 lane 任务体内发起并 join：必须立即完成（不得自锁等硬超时强拆）。
     * 生产形态 = MANUAL_COMMAND lane worker 上 ioBody join 写事务 future。
     */
    @Test
    public void writeInsideOwnLaneTaskBodyJoinCompletesImmediately() throws Exception {
        ModbusSource source = newSource();
        WriteRegisterResponse response = mock(WriteRegisterResponse.class);
        when(master.send(any(ModbusRequest.class))).thenReturn(response);
        when(master.isInitialized()).thenReturn(true);

        long startNanos = System.nanoTime();
        WriteRegisterResponse result = lane.submit(() ->
                source.writeRegister(100, 27).get(5, TimeUnit.SECONDS)).get(10, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        assertSame("lane 任务体内的写 join 必须拿到响应", response, result);
        assertTrue("写命令必须毫秒级完成，不得等硬超时强拆（实际 " + elapsedMs + "ms）",
                elapsedMs < 3_000L);
    }

    /**
     * 写后 lane 内立即可继续受理后续事务（队头未被自锁卡住）。
     */
    @Test
    public void laneAcceptsNextTransactionAfterWrite() throws Exception {
        ModbusSource source = newSource();
        when(master.send(any(ModbusRequest.class))).thenReturn(mock(WriteRegisterResponse.class));

        lane.submit(() -> source.writeRegister(0, 1).get(5, TimeUnit.SECONDS)).get(10, TimeUnit.SECONDS);
        assertEquals("写完成后同 lane 后继任务必须立即执行",
                "next", lane.submit(() -> "next").get(5, TimeUnit.SECONDS));
    }
}
