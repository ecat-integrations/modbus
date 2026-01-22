package com.ecat.integration.ModbusIntegration;

import com.ecat.core.Utils.TestTools;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.msg.*;
import org.junit.*;
import org.mockito.*;
import java.util.concurrent.*;
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
     * 测试资源释放
     */
    @Test
    public void testCloseModbus() throws Exception {
        modbusSource.closeModbus();
        verify(modbusMaster, times(0)).destroy();
    }

    /**
     * 测试资源释放
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
        final ModbusSource source = new ModbusSource(tcpInfo, 1, 300);
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
     * 测试获取等待队列长度
     */
    @Test
    public void testGetWaitingCount() throws InterruptedException {
        modbusSource.acquire();
        modbusSource.acquire();
        Thread.sleep(200);
        // 队列长度可能为0或1，取决于线程调度
        int waitingCount = modbusSource.getWaitingCount();
        assertTrue(waitingCount >= 0 && waitingCount <= 1);
    }
}
