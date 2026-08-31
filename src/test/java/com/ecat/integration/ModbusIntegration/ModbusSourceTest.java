package com.ecat.integration.ModbusIntegration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecat.core.Utils.TestTools;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.msg.*;
import org.junit.*;
import org.mockito.*;
import org.slf4j.LoggerFactory;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ModbusSource 单元测试
 * 覆盖构造、集成注册、资源释放、寄存器读写、锁队列等核心功能
 * 
 * @author coffee
 */
public class ModbusSourceTest {

    private ModbusInfo modbusInfo;
    private ModbusTcpInfo tcpInfo;
    private ModbusSerialInfo serialInfo;
    @Mock
    private ModbusMaster modbusMaster;

    private ModbusSource modbusSource;

    private AutoCloseable mockitoCloseable;

    @Before
    public void setUp() throws Exception {
        mockitoCloseable = MockitoAnnotations.openMocks(this);
        tcpInfo = Mockito.mock(ModbusTcpInfo.class);
        modbusInfo = Mockito.mock(ModbusTcpInfo.class);
        serialInfo = Mockito.mock(ModbusSerialInfo.class);
        TestTools.setPrivateField(tcpInfo, "slaveId", 1);
        TestTools.setPrivateField(modbusInfo, "slaveId", 1);
        TestTools.setPrivateField(serialInfo, "slaveId", 1);
        modbusSource = new ModbusSource(tcpInfo, 2, 1000);
        TestTools.setPrivateField(modbusSource, "modbusMaster", modbusMaster);
        TestTools.setPrivateField(modbusSource, "modbusInfo", tcpInfo);
    }

    @After
    public void tearDown() throws Exception {
        mockitoCloseable.close();
    }

    /**
     * 测试构造方法，验证参数初始化
     */
    @Test
    public void testConstructor_withTcpInfo() {
        ModbusSource source = new ModbusSource(tcpInfo, 3, 2000);
        assertEquals(3, source.getMaxWaiters());
        assertEquals(2000, source.getWaitTimeoutMs());
        assertEquals(tcpInfo, source.getModbusInfo());
    }

    /**
     * 死 source 复用 bug 回归（bug-record-20260728-170000）。
     * destroyResources() 置 destroyed 标志（P2 后死源显式标志；旧实现为 executor.shutdown()）；
     * 但 modbus4j 的 modbusMaster.destroy() 不重置 isInitialized flag（既知）。
     * 若 isModbusOpen() 只判 modbusMaster.isInitialized()，死 source 仍报 open → createOrGetSource 的死源清理
     * （!isModbusOpen()）失效 → 同连接后继新设备拿到已销毁的死 source → readAndUpdate 提交到死源的事务永不执行
     * → 无数据，须重启 core 才恢复。
     * 故 destroyResources 后（destroyed 置位），即使 master.isInitialized 仍 true，isModbusOpen 必须返回 false。
     */
    @Test
    public void isModbusOpen_falseAfterDestroyResources_evenIfMasterStillInitialized() {
        // 模拟 destroy 后 stale flag：modbusMaster.isInitialized() 仍 true
        when(modbusMaster.isInitialized()).thenReturn(true);
        assertTrue("销毁前应 open", modbusSource.isModbusOpen());
        modbusSource.destroyResources();   // destroyMaster（mock 无副作用）+ destroyed 置位
        assertFalse("destroyResources 后 isModbusOpen 必须为 false（destroyed 标志已置位）",
                modbusSource.isModbusOpen());
    }

    /**
     * 测试集成注册与移除
     */
    @Test
    public void testRegisterAndRemoveIntegration() throws Exception {
        String identity = "testDevice";
        modbusSource.registerIntegration(identity);
        Object registered = TestTools.getPrivateField(modbusSource, "registeredIntegrations");
        assertTrue(((java.util.List<?>) registered).contains(identity));
        modbusSource.removeIntegration(identity);
        assertFalse(((java.util.List<?>) registered).contains(identity));
    }

    /**
     * 测试通过 identity 释放资源
     */
    @Test
    public void testCloseModbusByIdentity() throws Exception {
        String identity = "testDevice";
        modbusSource.registerIntegration(identity);
        Object registered = TestTools.getPrivateField(modbusSource, "registeredIntegrations");
        assertTrue(((java.util.List<?>) registered).contains(identity));
        when(modbusMaster.isInitialized()).thenReturn(true);
        modbusSource.closeModbus(identity);
        verify(modbusMaster, times(1)).destroy();
    }

    /**
     * 测试寄存器读（Holding Registers）
     */
    @Test
    public void testReadHoldingRegisters() throws Exception {
        ReadHoldingRegistersResponse response = mock(ReadHoldingRegistersResponse.class);
        when(modbusMaster.send(any(ReadHoldingRegistersRequest.class))).thenReturn(response);

        CompletableFuture<ReadHoldingRegistersResponse> future = modbusSource.readHoldingRegisters(10, 2);
        assertEquals(response, future.get());
    }

    /**
     * 验证读失败时 ERROR 日志包含 modbusInfo（含端口名等连接信息）。
     * 多设备共总线断开时，需从日志直接定位是哪个物理口故障，而非只看到 slaveId/地址。
     * 覆盖 readHoldingRegistersWithSlaveId 的 catch 日志拼接（11 处 modbusInfo 追加的代表用例）。
     */
    @Test
    public void testReadFailureLogIncludesModbusInfo() throws Exception {
        // 真实 ModbusSerialInfo：toString() 含 portName（mock 的 toString 无意义，无法验证端口）
        ModbusSerialInfo serialInfo = new ModbusSerialInfo("/dev/ttyUSB10", 9600, 8,
                ModbusSerialInfo.ONE_STOP_BIT, ModbusSerialInfo.NO_PARITY, 2000, 1);
        // skipOpen=true：RTU 在无 SerialSource 时 openModbus 会抛；delegateMode=false：保留 executor 驱动 supplyAsync
        ModbusSource source = new ModbusSource(serialInfo, 2, 1000, true, false);
        TestTools.setPrivateField(source, "modbusMaster", modbusMaster);

        Logger logbackLogger = (Logger) LoggerFactory.getLogger(ModbusSource.class.getName());
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        try {
            when(modbusMaster.send(any(ModbusRequest.class)))
                    .thenThrow(new ModbusTransportException(1));

            CompletableFuture<?> future = source.readHoldingRegisters(110, 123);
            assertNull("传输异常时未来应完成为 null（保持现有契约）", future.get());

            String formatted = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.ERROR)
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (a, b) -> a + "\n" + b);
            assertTrue("ERROR 日志应含 modbusInfo、端口名与 slaveId，实际：\n" + formatted,
                    formatted.contains("modbusInfo=")
                            && formatted.contains("/dev/ttyUSB10")
                            && formatted.contains("slaveId: 1"));
        } finally {
            logbackLogger.detachAppender(appender);
        }
    }

    /**
     * 测试寄存器写（Write Registers）
     */
    @Test
    public void testWriteRegisters() throws Exception {
        WriteRegistersResponse response = mock(WriteRegistersResponse.class);
        when(modbusMaster.send(any(WriteRegistersRequest.class))).thenReturn(response);

        short[] values = new short[]{1, 2, 3};
        CompletableFuture<WriteRegistersResponse> future = modbusSource.writeRegisters(20, values);
        assertEquals(response, future.get());
    }

    /**
     * 测试线圈读写
     */
    @Test
    public void testReadAndWriteCoils() throws Exception {
        ReadCoilsResponse readResp = mock(ReadCoilsResponse.class);
        WriteCoilsResponse writeResp = mock(WriteCoilsResponse.class);
        when(modbusMaster.send(any(ReadCoilsRequest.class))).thenReturn(readResp);
        when(modbusMaster.send(any(WriteCoilsRequest.class))).thenReturn(writeResp);

        CompletableFuture<ReadCoilsResponse> readFuture = modbusSource.readCoils(0, 8);
        CompletableFuture<WriteCoilsResponse> writeFuture = modbusSource.writeCoils(0, new boolean[]{true, false, true});
        assertEquals(readResp, readFuture.get());
        assertEquals(writeResp, writeFuture.get());
    }

    /**
     * 测试锁队列 acquire/release
     */
    @Test
    public void testAcquireAndReleaseLock() {
        String key = modbusSource.acquire(100, TimeUnit.MILLISECONDS);
        assertNotNull(key);
        assertTrue(modbusSource.release(key));
    }

    /**
     * 并发测试3个线程抢夺锁资源，测试超时和全部完成
     */
    @Test
    public void testAcquireQueueOverflow() throws Exception {
        final ModbusSource source = new ModbusSource(tcpInfo, 1, 100);
        TestTools.setPrivateField(source, "modbusMaster", modbusMaster);
        TestTools.setPrivateField(source, "modbusInfo", tcpInfo);

        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(3);

        ConcurrentLinkedQueue<String> results = new ConcurrentLinkedQueue<>();

        Runnable task = () -> {
            try {
                startLatch.await();
                String key = source.acquire(300, TimeUnit.MILLISECONDS);
                if (key != null) {
                    results.add(key);
                    Thread.sleep(500); // 持有锁一段时间
                    source.release(key);
                } else {
                    results.add("timeout"); // 超时未获取到锁
                }

            } catch (Exception e) {
                results.add("error");
            } finally {
                finishLatch.countDown();
            }
        };

        executor.submit(task);
        executor.submit(task);
        executor.submit(task);

        startLatch.countDown();
        finishLatch.await();

        executor.shutdown();

        int successCount = 0;
        int timeoutCount = 0;
        for (String key : results) {
            if (key.equals("timeout")) timeoutCount++;
            else successCount++;
        }
        // 只有一个线程能成功获取锁，其他超时
        assertEquals(1, successCount);
        assertEquals(2, timeoutCount);

        // 再测试全部线程在时间内完成（加长超时时间）
        final ModbusSource source2 = new ModbusSource(tcpInfo, 2, 1000);
        TestTools.setPrivateField(source2, "modbusMaster", modbusMaster);
        TestTools.setPrivateField(source2, "modbusInfo", tcpInfo);

        ExecutorService executor2 = Executors.newFixedThreadPool(3);
        CountDownLatch startLatch2 = new CountDownLatch(1);
        CountDownLatch finishLatch2 = new CountDownLatch(3);

        ConcurrentLinkedQueue<String> results2 = new ConcurrentLinkedQueue<>();

        Runnable task2 = () -> {
            try {
                startLatch2.await();
                String key = source2.acquire(800, TimeUnit.MILLISECONDS);
                // results2.add(key);
                // if (key != null) {
                //     Thread.sleep(200);
                //     source2.release(key);
                // }
                if (key != null) {
                    results2.add(key);
                    Thread.sleep(200); // 持有锁一段时间
                    source2.release(key);
                } else {
                    results2.add("timeout"); // 超时未获取到锁
                }
            } catch (Exception e) {
                results2.add("error");
            } finally {
                finishLatch2.countDown();
            }
        };

        executor2.submit(task2);
        executor2.submit(task2);
        executor2.submit(task2);

        startLatch2.countDown();
        finishLatch2.await();

        executor2.shutdown();

        int successCount2 = 0;
        int timeoutCount2 = 0;
        for (String key : results2) {
            if (key.equals("timeout")) timeoutCount2++;
            else successCount2++;
        }
        // 3个线程都能在时间内完成
        assertEquals(3, successCount2);
        assertEquals(0, timeoutCount2);
    }

    /**
     * 测试资源状态
     */
    @Test
    public void testIsModbusOpen() throws Exception {
        when(modbusMaster.isInitialized()).thenReturn(true);
        assertTrue(modbusSource.isModbusOpen());
        when(modbusMaster.isInitialized()).thenReturn(false);
        assertFalse(modbusSource.isModbusOpen());
    }

    /**
     * 测试获取等待队列长度：超时等待者必须已从 waitQueue 自行摘除（死 key 防线）。
     *
     * <p>原形态为同线程双 acquire + sleep(200)：第二个 acquire 在本线程 park 满
     * waitTimeoutMs(1000ms) 后超时自行摘除，测量时队列恒为 0，断言 0≤x≤1 永真（无检出力）。
     *
     * <p>重写语义（以源码为准）：getWaitingCount()=waitQueue.size()=当前 park 中的等待者数。
     * 确定性可观测态 = 等待者超时摘除自身后精确为 0（ModbusSource.acquire 超时分支
     * waitQueue.remove）；「park 中途 ==1」只能靠竞态采样观测，违背确定性同步纪律，故取 ==0。
     * 辅助线程持锁经 latch 确认（等事件已发生，不猜时间）；本线程用显式 150ms 短超时
     * acquire 替代原默认 1000ms park。
     */
    @Test
    public void testGetWaitingCount() throws Exception {
        // 辅助线程持锁（生产形态：持锁者恒为别的事务线程），latch 确保持锁在先
        final CountDownLatch held = new CountDownLatch(1);
        final AtomicReference<String> heldKey = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            heldKey.set(modbusSource.acquire());
            held.countDown();
        }, "modbus-test-lock-holder");
        holder.setDaemon(true);
        holder.start();
        assertTrue("持锁辅助线程必须在期限内取得锁", held.await(5, TimeUnit.SECONDS));
        assertNotNull("持锁 key 不应为 null", heldKey.get());

        // 本线程短超时 acquire：锁忙进 waitQueue park，超时返回 null 前已自行摘除
        String busyKey = modbusSource.acquire(150, TimeUnit.MILLISECONDS);
        assertNull("锁被持有时 acquire 应超时返回 null", busyKey);
        assertEquals("超时等待者必须已从等待队列摘除（无死 key 残留）",
                0, modbusSource.getWaitingCount());

        // 释放辅助线程持有的锁；无 waiter 泄漏时快速路径立即可得
        assertTrue(modbusSource.release(heldKey.get()));
        String next = modbusSource.acquire(200, TimeUnit.MILLISECONDS);
        assertNotNull("释放后应立即可再次取得锁（无残留 waiter 干扰）", next);
        modbusSource.release(next);
    }
}
