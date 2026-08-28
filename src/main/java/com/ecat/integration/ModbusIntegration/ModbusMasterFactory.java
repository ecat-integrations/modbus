package com.ecat.integration.ModbusIntegration;

import com.ecat.integration.SerialIntegration.SerialSource;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.ip.IpParameters;
import com.serotonin.modbus4j.ip.tcp.TcpMaster;

import java.lang.reflect.Field;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ModbusMaster工厂类，根据设备信息类型创建TCP或串行Master。
 *
 * <p>工厂还负责管理传输资源的生命周期：RTU 模式下，
 * SerialSource 与 ModbusMaster 的关联由工厂内部跟踪，
 * 通过 {@link #destroyMaster(ModbusMaster)} 统一释放。
 *
 * @author coffee
 */
public class ModbusMasterFactory {

    private static final Log log = LogFactory.getLogger(ModbusMasterFactory.class);

    /**
     * RTU 模式下 master → SerialSource 的映射，用于销毁时释放串口资源
     */
    private static final Map<ModbusMaster, SerialSource> serialMasterRegistry = new ConcurrentHashMap<>();

    /**
     * RTU 模式下 master → ModbusSerialPortWrapper 的映射，用于销毁时恢复 event adapter
     */
    private static final Map<ModbusMaster, ModbusSerialPortWrapper> wrapperRegistry = new ConcurrentHashMap<>();

    /**
     * 创建ModbusMaster实例（仅 TCP）
     * @param info Modbus设备信息（仅支持 TCP）
     * @return ModbusMaster实例
     * @throws ModbusInitException 初始化异常
     */
    public static ModbusMaster createModbusMaster(ModbusInfo info) throws ModbusInitException {
        if (info instanceof ModbusTcpInfo) {
            ModbusFactory factory = new ModbusFactory();
            return createTcpMaster((ModbusTcpInfo) info, factory);
        } else if (info instanceof ModbusSerialInfo) {
            throw new IllegalArgumentException("RTU 设备必须使用 createSerialMaster(info, serialSource)");
        } else {
            throw new IllegalArgumentException("未知的Modbus协议类型");
        }
    }

    /**
     * 创建串行 ModbusMaster（通过 serial integration 管理串口）
     *
     * <p>创建后自动注册 master → SerialSource 映射，
     * 销毁时通过 {@link #destroyMaster(ModbusMaster)} 统一释放。
     *
     * @param serialInfo 串口配置
     * @param serialSource 来自 serial integration 的串口资源
     * @return ModbusMaster实例
     * @throws ModbusInitException 初始化异常
     */
    public static ModbusMaster createSerialMaster(ModbusSerialInfo serialInfo, SerialSource serialSource) throws ModbusInitException {
        ModbusFactory factory = new ModbusFactory();
        ModbusSerialPortWrapper serialPortWrapper = new ModbusSerialPortWrapper(serialInfo, serialSource);
        ModbusMaster modbusMaster = factory.createRtuMaster(serialPortWrapper);
        modbusMaster.setTimeout(serialInfo.getTimeout());
        // F-43 陈旧 RX 防护（同 TCP 路径，RtuMaster 与 TcpMaster 同走 MessageControl.waitingRoom）：
        // 超时/强拆后迟到应答字节在静默超阈值后到达即丢弃；内核层残留另由 serial 的
        // recoverWedgedPort 重开清洗（F-43 清洗②）兜底。
        modbusMaster.setDiscardDataDelay(serialInfo.getTimeout());
        // 注册映射：销毁时需要恢复 event adapter + 释放 SerialSource
        serialMasterRegistry.put(modbusMaster, serialSource);
        wrapperRegistry.put(modbusMaster, serialPortWrapper);
        return modbusMaster;
    }

    /**
     * 统一销毁 ModbusMaster 及其关联的传输资源。
     *
     * <p>处理 TCP 和 RTU 的差异：
     * <ul>
     *   <li>TCP：仅销毁 ModbusMaster</li>
     *   <li>RTU（通过 serial integration）：销毁 ModbusMaster + 释放 SerialSource</li>
     * </ul>
     *
     * @param master 要销毁的 ModbusMaster
     */
    public static void destroyMaster(ModbusMaster master) {
        SerialSource serialSource = serialMasterRegistry.remove(master);
        ModbusSerialPortWrapper wrapper = wrapperRegistry.remove(master);
        if (master != null && master.isInitialized()) {
            master.destroy();
        }
        // 恢复 event adapter（wrapper.destroy() 内部会检查 adapterPaused 标志）
        if (wrapper != null) {
            wrapper.destroy();
        }
        if (serialSource != null) {
            serialSource.closePort();
        }
    }

    /**
     * 挂死传输强拆（Q-1/A2 自愈）：从旁关闭底层传输原语，使钉死在 {@code master.send}
     * 阻塞读写上的车道线程立即异常返回。
     *
     * <p>禁止在此调用 {@code master.destroy()}/{@code master.init()}——modbus4j TcpMaster 的
     * {@code sendImpl}/{@code init}/{@code destroy} 同步在同一 master 监视锁上，持锁的挂死
     * send 不返回时调用方将永久阻塞（把挂死扩散到超时调度线程）。故按传输类型绕过 master API：
     * <ul>
     *   <li>TCP：反射关闭 {@code TcpMaster.socket}（私有字段，modbus4j v3.1.9 固定版本；
     *       socket.close() 是线程安全的官方解除阻塞手段，阻塞在 socket 读写上的线程立即抛
     *       IOException）——keepAlive master 下一事务走 sendImpl 既有重连路径自愈；</li>
     *   <li>RTU：挂死写发生在共享串口 fd 上，经 {@link #serialMasterRegistry} 反查
     *       SerialSource，委托 {@code SerialSource.recoverWedgedPort}（close+reopen）强拆；
     *       RTU master 每事务 openConnection 重取串口流，下一事务自动用新 fd。</li>
     * </ul>
     *
     * @param master 挂死传输所属的 master
     * @param reason 触发原因（日志定位用）
     */
    public static void forceRecover(ModbusMaster master, String reason) {
        if (master instanceof TcpMaster) {
            closeTcpSocket((TcpMaster) master, reason);
        }
        SerialSource serialSource = serialMasterRegistry.get(master);
        if (serialSource != null) {
            serialSource.recoverWedgedPort(reason);
        }
    }

    /**
     * 反射关闭 TcpMaster 的私有 socket 字段（版本 v3.1.9 固定，字段名 socket）。
     * 拿不到（字段改名/未建连）时仅告警——强拆失败不致命，下一事务超时路径会再次尝试。
     */
    private static void closeTcpSocket(TcpMaster master, String reason) {
        try {
            Field socketField = TcpMaster.class.getDeclaredField("socket");
            socketField.setAccessible(true);
            Socket socket = (Socket) socketField.get(master);
            if (socket != null && !socket.isClosed()) {
                socket.close();
                log.warn("Wedge recovery({}) closed TcpMaster socket forcibly", reason);
            }
        } catch (Exception e) {
            log.warn("Wedge recovery({}) failed to close TcpMaster socket: {}", reason, e.getMessage());
        }
    }

    private static ModbusMaster createTcpMaster(ModbusTcpInfo tcpInfo, ModbusFactory factory) {
        IpParameters ipParams = new IpParameters();
        ipParams.setHost(tcpInfo.getIpAddress());
        ipParams.setPort(tcpInfo.getPort());
        if(tcpInfo.getProtocol() == ModbusProtocol.RTU_OVER_TCP) {
            ipParams.setEncapsulated(true); // RTU over TCP
        } else {
            ipParams.setEncapsulated(false); // 标准 Modbus TCP
        }
        // keepAlive=true：master 是长生命周期资源，连接建立一次持续复用。
        // keepAlive=false 时 modbus4j 每个事务都 openConnection/closeConnection，
        // 每次建连都新建名为 "Modbus4J TcpMaster" 的读线程（TcpMaster.openConnection →
        // StreamTransport.start），全量轮询下实测线程创建率 ~11/s；32 位 Win2003 部署
        // 线程硬上限 ≤100，churn 必须归零，故必须保持连接。
        // 故障恢复走 modbus4j 事务内重连（sendImpl 捕获异常后 openConnection 重发一次，
        // 重连的是同一 master 实例），master 对象本身只在连接重新注册时才重建。
        ModbusMaster master = factory.createTcpMaster(ipParams, true);
        master.setTimeout(tcpInfo.getTimeout()); // 设置 TCP 事务超时（毫秒）
        // F-43 modbus 对齐 serial 发前清缓冲纪律：复用连接（keepAlive）上被强拆/超时事务的迟到
        // 应答字节会与下一事务响应在 MBAP 流上错位。modbus4j MessageControl 内建 discardDataDelay
        // ——数据在静默超过阈值后到达即先清空解析缓冲再入栈（读线程内执行，无跨线程读竞态）。
        // 阈值取请求超时：静默超过一次请求超时后到达的字节必是死事务残留（合法响应在超时内必达，
        // 分段响应的段间隔也远小于该值）。
        master.setDiscardDataDelay(tcpInfo.getTimeout());
        return master;
    }
}
