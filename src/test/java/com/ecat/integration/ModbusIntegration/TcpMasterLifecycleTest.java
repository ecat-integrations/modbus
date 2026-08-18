package com.ecat.integration.ModbusIntegration;

import com.serotonin.modbus4j.msg.ReadHoldingRegistersResponse;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * TcpMaster 生命周期回归测试（A6：thread churn 11/s→0）。
 *
 * <p>背景：ModbusMasterFactory 曾以 keepAlive=false 创建 TcpMaster，modbus4j 在该模式下
 * <b>每个事务</b>都 openConnection/closeConnection，而每次 openConnection 都会
 * {@code StreamTransport.start("Modbus4J TcpMaster")} 新建一个读线程（TcpMaster.java:190/320、
 * InputStreamListener.start）。全量轮询下实测线程创建率 ~11/s，32 位 Win2003 部署（线程硬上限
 * ≤100）下为生死项。</p>
 *
 * <p>测试判定口径：master 侧每建立一条 TCP 连接 = 创建 1 个 "Modbus4J TcpMaster" 读线程。
 * 因此「连续 N 次成功事务后服务端只 accept 过 1 条连接」等价于「master 连接（含读线程）复用、
 * 不重建」，全程确定性同步（CompletableFuture.get 超时兜底，无 Thread.sleep）。</p>
 *
 * @author coffee
 */
public class TcpMasterLifecycleTest {

    private MbapServer server;
    private ModbusSource source;

    @After
    public void tearDown() throws Exception {
        if (source != null) {
            source.destroyResources(); // 销毁 master 连接（含读线程）+ executor
        }
        if (server != null) {
            server.close();
        }
    }

    /**
     * 同一设备连续 5 次读事务：master 连接应复用不重建。
     * 修复前（keepAlive=false）每个事务新建一条连接 → accept=5，本测试红。
     */
    @Test
    public void consecutiveTransactionsReuseSingleConnection() throws Exception {
        server = new MbapServer(0);
        source = new ModbusSource(new ModbusTcpInfo("127.0.0.1", server.port(), 1), 1, 2000);
        assertTrue("服务端在线时 init 后应视为 open", source.isModbusOpen());

        for (int i = 0; i < 5; i++) {
            ReadHoldingRegistersResponse resp = source.readHoldingRegisters(0, 10).get(15, TimeUnit.SECONDS);
            assertNotNull("第 " + (i + 1) + " 次事务应成功", resp);
        }

        assertEquals("5 次成功事务必须复用同一条连接（每条连接恰好 1 个读线程，连接数=读线程数）",
                1, server.acceptedConnections());
        assertEquals("确认 5 次事务确实到达服务端（防空转通过）", 5, server.receivedRequests());
    }

    /**
     * 故障路径预期：连接中途静默失效（模拟设备/中间网关黑洞）时，
     * 同一 master 实例事务内重连恰好 1 次，后续健康事务不再建新连接。
     * 修复前（keepAlive=false）每个事务独立建连 → accept=5，本测试红。
     */
    @Test
    public void blackholeFailureRecoversWithSingleReconnect() throws Exception {
        server = new MbapServer(0);
        // 事务超时 300ms：黑洞事务经 modbus4j 内部重试（retries=2）后抛超时，
        // keepAlive 路径触发事务内重连；总耗时约 3×300ms，确定性由内核超时驱动。
        source = new ModbusSource(
                new ModbusTcpInfo("127.0.0.1", server.port(), 1, ModbusProtocol.TCP, 300), 1, 2000);
        // 第 1 条连接服务完 2 个请求后静默丢弃后续所有请求（含 modbus4j 的同连接重发）
        server.degradeFirstConnectionAfter(2);

        for (int i = 0; i < 5; i++) {
            ReadHoldingRegistersResponse resp = source.readHoldingRegisters(0, 10).get(30, TimeUnit.SECONDS);
            assertNotNull("含 1 次注入故障的 5 次事务都应成功（事务内重连重发）", resp);
        }

        assertEquals("受控恢复：初始连接 + 故障重连各 1 条，后续健康事务不得再建新连接",
                2, server.acceptedConnections());
        assertTrue("确认事务确实到达服务端", server.receivedRequests() >= 5);
    }

    /**
     * 对端在注册时不可达：init 失败不得终生化——源照常构造（openModbus 吞 init 异常是既有契约），
     * 对端恢复后首次使用即经事务内重连自动恢复，且只建 1 条连接。
     */
    @Test
    public void initFailureWhenEndpointDownStillRecoversOnFirstUse() throws Exception {
        int freePort = findFreePort();
        source = new ModbusSource(
                new ModbusTcpInfo("127.0.0.1", freePort, 1, ModbusProtocol.TCP, 300), 1, 2000);
        assertFalse("init 时对端不可达：master 未初始化，源不得标记 open", source.isModbusOpen());

        server = new MbapServer(freePort); // 对端恢复
        ReadHoldingRegistersResponse resp = source.readHoldingRegisters(0, 10).get(30, TimeUnit.SECONDS);
        assertNotNull("init 失败后应能自愈（首次使用事务内重连）", resp);
        assertEquals(1, server.acceptedConnections());
    }

    /**
     * 取一个当前无人监听的端口（bind(0) 后立即释放）。
     */
    private static int findFreePort() throws IOException {
        ServerSocket ss = new ServerSocket(0);
        int port = ss.getLocalPort();
        ss.close();
        return port;
    }

    /**
     * 测试内嵌的最小 Modbus TCP（MBAP/XA，FC=03）服务端。
     *
     * <p>职责：统计 accept 的连接数（= master 侧读线程创建数）与收到的请求数；
     * 支持「第 1 条连接服务 N 个请求后转入黑洞」（读请求但不响应，模拟静默 IO 故障）。
     * 每条连接一个 handler 线程，客户端断开即退出，无状态残留。</p>
     */
    private static final class MbapServer implements Closeable {
        private final ServerSocket serverSocket;
        private final ExecutorService connectionPool;
        private final Thread acceptThread;
        private final AtomicInteger acceptedConnections = new AtomicInteger();
        private final AtomicInteger receivedRequests = new AtomicInteger();

        /** 第 1 条连接服务完该请求数后黑洞化；Integer.MAX_VALUE = 永不黑洞 */
        private volatile int healthyRequestsOnFirstConnection = Integer.MAX_VALUE;

        MbapServer(int port) throws IOException {
            serverSocket = new ServerSocket(port); // port=0 由内核分配临时端口，mvnd 并行安全
            connectionPool = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "mbap-test-conn");
                t.setDaemon(true);
                return t;
            });
            acceptThread = new Thread(this::acceptLoop, "mbap-test-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        int acceptedConnections() {
            return acceptedConnections.get();
        }

        int receivedRequests() {
            return receivedRequests.get();
        }

        void degradeFirstConnectionAfter(int healthyRequests) {
            this.healthyRequestsOnFirstConnection = healthyRequests;
        }

        private void acceptLoop() {
            while (true) {
                try {
                    Socket socket = serverSocket.accept();
                    final int connectionOrdinal = acceptedConnections.incrementAndGet();
                    connectionPool.execute(() -> serveConnection(socket, connectionOrdinal));
                } catch (IOException e) {
                    return; // ServerSocket 关闭（close()）→ 退出接受循环
                }
            }
        }

        private void serveConnection(Socket socket, int connectionOrdinal) {
            try (Socket s = socket;
                 DataInputStream in = new DataInputStream(s.getInputStream());
                 OutputStream out = s.getOutputStream()) {
                int servedOnThisConnection = 0;
                while (true) {
                    // MBAP 头 7 字节：事务ID(2) 协议ID(2) 长度(2) 单元ID(1)
                    int tidHi = in.read();
                    if (tidHi < 0) {
                        return; // 客户端关闭连接
                    }
                    int tidLo = in.read();
                    in.read();
                    in.read(); // 协议 ID 固定 0
                    int lenHi = in.read();
                    int lenLo = in.read();
                    int unitId = in.read();
                    int length = (lenHi << 8) | lenLo; // 头之后字节数（含单元ID）
                    byte[] pdu = new byte[length - 1];
                    in.readFully(pdu);

                    servedOnThisConnection++;
                    receivedRequests.incrementAndGet();
                    if (connectionOrdinal == 1 && servedOnThisConnection > healthyRequestsOnFirstConnection) {
                        continue; // 黑洞：已读走请求但不响应，保持连接
                    }
                    if (pdu[0] != 0x03) {
                        throw new IOException("测试服务端只支持 FC=03，收到 FC=" + pdu[0]);
                    }
                    writeReadHoldingRegistersResponse(out, tidHi, tidLo, unitId, pdu);
                }
            } catch (IOException e) {
                // 客户端断开（重连时旧连接被 master 关闭）→ handler 正常退出
            }
        }

        private static void writeReadHoldingRegistersResponse(
                OutputStream out, int tidHi, int tidLo, int unitId, byte[] requestPdu) throws IOException {
            int registerCount = ((requestPdu[3] & 0xFF) << 8) | (requestPdu[4] & 0xFF);
            int byteCount = registerCount * 2;
            int length = 3 + byteCount; // 单元ID(1) + 功能码(1) + 字节数(1) + 数据
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(tidHi);
            frame.write(tidLo);
            frame.write(0);
            frame.write(0);
            frame.write(length >> 8);
            frame.write(length & 0xFF);
            frame.write(unitId);
            frame.write(0x03);
            frame.write(byteCount);
            for (int i = 0; i < byteCount; i++) {
                frame.write(0x11);
            }
            out.write(frame.toByteArray());
            out.flush();
        }

        @Override
        public void close() throws IOException {
            serverSocket.close(); // 解除 accept 阻塞
            connectionPool.shutdownNow();
        }
    }
}
