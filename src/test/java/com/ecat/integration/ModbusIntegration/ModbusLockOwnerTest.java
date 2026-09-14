package com.ecat.integration.ModbusIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.ecat.core.CommTrace.CommTraceBuffer;
import com.ecat.core.CommTrace.CommTraceDirection;
import com.ecat.core.CommTrace.CommTraceEvent;
import com.ecat.core.CommTrace.CommTraceFilter;
import com.ecat.core.CommTrace.CommTraceTransport;
import com.ecat.core.CommTrace.ResourceOwner;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.msg.ModbusRequest;
import com.serotonin.modbus4j.msg.ModbusResponse;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersResponse;

/**
 * ModbusSource 锁 owner 生命周期与捕获点权威归因（io-resource-owner 设计 §5.3 锁 + §8 行
 * 「多 slave 逐请求归因」）：acquire/acquirePollingBounded 授予点登记 lockAcquireOwner、
 * release 与幽灵锁收割清除；DeviceSpecificModbusSource 包装器 acquire/acquirePollingBounded
 * 自动注入自身 owner（集成事务代码零改动）；LEGACY 无设备身份不注入（过渡期行为不变）；
 * sendTraced TX/RX 捕获点读当前持锁 owner 作权威参数（同连接多 slave 交替事务逐笔归属）。
 */
public class ModbusLockOwnerTest {

    private static final String COORDINATE = "com.ecat:integration-mb-lock-test";

    private static ResourceOwner deviceOwner(String entryId, String deviceId) {
        return ResourceOwner.device(COORDINATE, entryId, deviceId);
    }

    /** 跳过 openModbus 的裸源（同包可达 protected 构造；锁/账本不依赖 master）。 */
    private static ModbusSource bareSource(ModbusInfo info) {
        return new ModbusSource(info, 1, 100, true, false);
    }

    private static ModbusSerialInfo serialInfo(String portName) {
        return new ModbusSerialInfo(portName, 9600, 8, 1, 0, 500, 1);
    }

    private static void setMaster(ModbusSource source, ModbusMaster master) {
        try {
            Field f = ModbusSource.class.getDeclaredField("modbusMaster");
            f.setAccessible(true);
            f.set(source, master);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ==================== 锁 owner 生命周期（§5.3 同 serial） ====================

    /** 阻塞 acquire 授予点登记 owner，release 清除（当前住户记账）。 */
    @Test
    public void acquireRegistersOwnerAndReleaseClearsIt() {
        ModbusSource source = bareSource(serialInfo("/dev/null"));
        ResourceOwner owner = deviceOwner("entry-1", "dev-1");

        String key = source.acquire(owner);
        assertNotNull(key);
        assertEquals("授予点登记持锁 owner", owner, source.getLockAcquireOwner());

        source.release(key);
        assertNull("release 清除 owner（同一状态机）", source.getLockAcquireOwner());
    }

    /** 轮询契约快路径授予点同样登记 owner（无竞争轮次零等待直达，同 acquire 状态机）。 */
    @Test
    public void pollingAcquireFastPathRegistersOwner() throws Exception {
        ModbusSource source = bareSource(serialInfo("/dev/null"));
        ResourceOwner owner = deviceOwner("entry-1", "dev-2");

        String key = source.acquirePollingBounded(owner, 500).get(5, TimeUnit.SECONDS);
        assertNotNull(key);
        assertEquals(owner, source.getLockAcquireOwner());

        source.release(key);
        assertNull(source.getLockAcquireOwner());
    }

    /** 等待授予（队头交接）路径同样登记 owner：预算内等到锁的轮次走同一状态机。 */
    @Test
    public void pollingAcquireWaitGrantRegistersOwner() throws Exception {
        ModbusSource source = bareSource(serialInfo("/dev/null"));
        String held = source.acquire(deviceOwner("entry-1", "dev-holder"));
        assertNotNull(held);
        ResourceOwner waiter = deviceOwner("entry-1", "dev-wait");

        CompletableFuture<String> waiting = source.acquirePollingBounded(waiter, 2000);
        // 先证明等待者已入队再 release（事件已发生断言，非固定 sleep 猜测）：否则 release 抢在
        // 旁池任务前会发生快路径授予，测的不是队头交接形态
        awaitWaitingCount(source, 1);

        source.release(held);
        String key = waiting.get(5, TimeUnit.SECONDS);
        assertNotNull("预算内等到锁（队头交接）", key);
        assertEquals("队头交接授予点登记等待者 owner", waiter, source.getLockAcquireOwner());
        source.release(key);
    }

    /** 等待者入队断言（deadline 轮询验证事件已发生，超时即失败；bareSource 旁池任务异步入队）。 */
    private static void awaitWaitingCount(ModbusSource source, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (source.getWaitingCount() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("等待者未在 5s 内入队: expected>=" + expected
                        + ", actual=" + source.getWaitingCount());
            }
        }
    }

    /** 幽灵锁收割清 owner 且不粘到下一笔授予（收割与 release 同一状态机）。 */
    @Test
    public void ghostReapClearsOwnerNotStickingToNextGrant() throws Exception {
        ModbusSource source = bareSource(serialInfo("/dev/null"));
        source.setGhostReapThresholdMsForTest(100);

        String key1 = source.acquire(deviceOwner("entry-1", "dev-stale"));
        assertNotNull(key1);
        // 越过收割阈值（墙钟等待为阈值粒度所限的下限，非同步手段；闩永不释放，仅借其超时）
        new CountDownLatch(1).await(300, TimeUnit.MILLISECONDS);

        String key2 = source.acquire(deviceOwner("entry-1", "dev-fresh"));
        assertNotNull("幽灵锁被收割，新事务可授予", key2);
        assertEquals("收割清掉旧 owner，登记的是新事务自己的 owner",
                deviceOwner("entry-1", "dev-fresh"), source.getLockAcquireOwner());
        source.release(key2);
    }

    // ==================== 包装器自动注入（§5.3 集成事务代码零改动） ====================

    /** 包装器 acquire/acquirePollingBounded 把自身 owner 注入共享源锁（同连接多 slave 各注各的）。 */
    @Test
    public void wrapperAcquireInjectsOwnOwnerIntoSharedLock() throws Exception {
        ModbusInfo info = serialInfo("/dev/null");
        ModbusSource shared = bareSource(info);
        DeviceSpecificModbusSource deviceA =
                new DeviceSpecificModbusSource(shared, info, deviceOwner("entry-1", "dev-a"), null);
        DeviceSpecificModbusSource deviceB =
                new DeviceSpecificModbusSource(shared, info, deviceOwner("entry-1", "dev-b"), null);

        String keyA = deviceA.acquire();
        assertNotNull(keyA);
        assertEquals("包装器注入自身 owner（事务代码零改动）", deviceOwner("entry-1", "dev-a"),
                shared.getLockAcquireOwner());

        assertNull("锁忙：另一设备轮询预算耗尽后弃轮",
                deviceB.acquirePollingBounded(50).get(5, TimeUnit.SECONDS));

        deviceA.release(keyA);
        String keyB = deviceB.acquire();
        assertNotNull(keyB);
        assertEquals("换设备持锁换 owner（同连接判官=源锁）", deviceOwner("entry-1", "dev-b"),
                shared.getLockAcquireOwner());
        deviceB.release(keyB);
    }

    /** LEGACY 包装器不注入锁权威：无设备身份字段，注入会压制线程 MDC 归因（过渡期行为不变）。 */
    @Test
    public void legacyWrapperAcquireDoesNotInjectOwner() {
        ModbusInfo info = serialInfo("/dev/null");
        ModbusSource shared = bareSource(info);
        DeviceSpecificModbusSource legacy =
                new DeviceSpecificModbusSource(shared, info, ResourceOwner.legacy("legacy-identity"), null);

        String key = legacy.acquire();
        assertNotNull(key);
        assertNull("LEGACY 不参与锁权威注入", shared.getLockAcquireOwner());
        legacy.release(key);
    }

    // ==================== 捕获点权威归因（ModbusSource sendTraced） ====================

    /** 最小桩 master（ModbusMaster 抽象类，send 为 final 模板方法委托 sendImpl）。 */
    abstract static class StubMaster extends ModbusMaster {
        @Override public void init() {}
        @Override public void destroy() {}
    }

    private static ModbusSource tracedSource(String portName) {
        ModbusSource source = new ModbusSource(serialInfo(portName), 1, 100, true, false);
        StubMaster master = new StubMaster() {
            { initialized = true; }
            @Override
            public ModbusResponse sendImpl(ModbusRequest request) {
                // 响应构造器全包级保护，mock 具体响应类（编码空帧，归因断言不依赖帧内容）
                return mock(ReadHoldingRegistersResponse.class);
            }
        };
        setMaster(source, master);
        return source;
    }

    private static List<CommTraceEvent> queryFrames(String portName, long since) {
        return CommTraceBuffer.instance().query(
                new CommTraceFilter(CommTraceTransport.SERIAL, portName, null, null, null), 10, since);
    }

    /** 持锁事务的 TX/RX 帧归属当前持锁 owner（modbus 事务恒在锁内，权威值总可得）。 */
    @Test
    public void heldLockSendAttributesToDeviceOwner() throws Exception {
        ModbusSource source = tracedSource("/dev/tty-mb-attr");
        long since = CommTraceBuffer.instance().latestSeq();

        String key = source.acquire(deviceOwner("entry-1", "dev-tx"));
        assertNotNull(key);
        source.readHoldingRegistersWithSlaveId(1, 0, 2).get(10, TimeUnit.SECONDS);
        source.release(key);

        List<CommTraceEvent> events = queryFrames("/dev/tty-mb-attr", since);
        assertEquals("TX+RX 各 1 帧", 2, events.size());
        assertEquals(CommTraceDirection.TX, events.get(0).getDirection());
        assertEquals(CommTraceDirection.RX, events.get(1).getDirection());
        assertEquals("TX 权威归属持锁 owner 的 deviceId", "dev-tx", events.get(0).getDeviceId());
        assertEquals("RX 权威归属持锁 owner 的 deviceId", "dev-tx", events.get(1).getDeviceId());
        assertEquals(COORDINATE, events.get(0).getCoordinate());
    }

    /** 无锁路径现状保持：owner 已清（或未注入）时不误挂，如实 null。 */
    @Test
    public void unlockedSendKeepsCurrentBehaviorNullDevice() throws Exception {
        ModbusSource source = tracedSource("/dev/tty-mb-nolock");
        long since = CommTraceBuffer.instance().latestSeq();

        source.readHoldingRegistersWithSlaveId(1, 0, 2).get(10, TimeUnit.SECONDS);

        List<CommTraceEvent> events = queryFrames("/dev/tty-mb-nolock", since);
        assertEquals("读事务 TX+RX 各 1 帧", 2, events.size());
        assertEquals(CommTraceDirection.TX, events.get(0).getDirection());
        assertEquals(CommTraceDirection.RX, events.get(1).getDirection());
        assertNull("无锁发送不挂注册 owner（权威=当前住户）", events.get(0).getDeviceId());
        assertNull("无锁接收同样如实无主", events.get(1).getDeviceId());
        assertNull(events.get(0).getCoordinate());
    }

    /**
     * 同连接多 slave 逐请求归因（§8 RTU 行判官形态）：一共享源两设备包装器交替持锁发事务，
     * 帧归属逐笔与持锁者对应（slaveId 逐请求注入由包装器既有语义承担，owner 同路径注入）。
     */
    @Test
    public void multiSlavePerRequestAttributionOnSharedConnection() throws Exception {
        ModbusInfo info = serialInfo("/dev/tty-mb-shared");
        ModbusSource shared = tracedSource("/dev/tty-mb-shared");
        DeviceSpecificModbusSource slaveA =
                new DeviceSpecificModbusSource(shared, info, deviceOwner("entry-a", "dev-a"), null);
        DeviceSpecificModbusSource slaveB =
                new DeviceSpecificModbusSource(shared, info, deviceOwner("entry-b", "dev-b"), null);
        long since = CommTraceBuffer.instance().latestSeq();

        String keyA = slaveA.acquire();
        assertNotNull(keyA);
        slaveA.readHoldingRegisters(0, 2).get(10, TimeUnit.SECONDS);
        slaveA.release(keyA);

        String keyB = slaveB.acquire();
        assertNotNull(keyB);
        slaveB.readHoldingRegisters(0, 2).get(10, TimeUnit.SECONDS);
        slaveB.release(keyB);

        List<CommTraceEvent> events = queryFrames("/dev/tty-mb-shared", since);
        assertEquals("两事务各 TX+RX", 4, events.size());
        // query 返回旧→新序：前两帧=A 事务，后两帧=B 事务
        assertEquals("第一笔 TX 归属 dev-a", "dev-a", events.get(0).getDeviceId());
        assertEquals("第一笔 RX 归属 dev-a", "dev-a", events.get(1).getDeviceId());
        assertEquals("第二笔 TX 归属 dev-b（同连接判官=源锁，逐笔交替）", "dev-b", events.get(2).getDeviceId());
        assertEquals("第二笔 RX 归属 dev-b", "dev-b", events.get(3).getDeviceId());
    }
}
