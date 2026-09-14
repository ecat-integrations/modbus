/*
 * Copyright (c) 2026 ECAT Team
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ecat.integration.ModbusIntegration.Slave;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.ecat.core.CommTrace.ResourceOwner;
import com.ecat.core.Integration.IntegrationInfo;
import com.ecat.core.Integration.IntegrationLoadOption;
import com.ecat.integration.ModbusIntegration.ModbusIntegration;
import com.ecat.integration.SerialIntegration.SerialInfo;
import com.ecat.integration.SerialIntegration.SerialIntegration;
import com.ecat.integration.SerialIntegration.SerialSource;

/**
 * registerSlave/unregisterSlave 的资源记账窄测试：只锁注册中心的账目语义
 * （同键去重、摘空即拆、未知键幂等），不铺寄存器读写等运行时行为。
 *
 * <p>经 {@link ModbusIntegration#registerSlave} / {@link ModbusIntegration#unregisterSlave}
 * 公开入口驱动；账目读面（getServer/getCallbackCount）在 ModbusSlaveRegistry 上公开，
 * 但 ModbusIntegration 未暴露该字段，测试用反射只读取用（本仓既有测试同款手法）。
 *
 * <p>用 TCP 配置走账：registerSlave 对 TCP 是纯委托注册中心，不涉及串口资源，
 * 记账语义与 RTU 路径同一份实现（serverMap 按 connectionId 键控）。
 *
 * <p>RTU 串口生命周期（bug-record-20260913-124500 A/B）：spy serial 计数注册次数 +
 * close 计数桩观测释放——串口获取只在 server 首建时发生一次（重复注册不重复占账）、
 * 摘空拆 server 无条件释放（never-start 也有 closePort）、并发败者即取即还。
 */
public class ModbusSlaveRegistrationAccountingTest {

    private static final String CONNECTION_ID = "0.0.0.0:15020";

    private ModbusIntegration integration;
    private ModbusSlaveRegistry registry;

    /** RTU 用例的串口获取记录面：spy serial 的 register 每次回放一个 close 计数桩。 */
    private SerialIntegration serialSpy;
    private final List<CloseCountingSerialSource> acquiredSources = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        integration = new ModbusIntegration();
        registry = slaveRegistryOf(integration);
    }

    @After
    public void tearDown() {
        // 未 start 过任何 server，clear 只清空账目，不触发真实端口/线程回收
        registry.clear();
        acquiredSources.clear();
    }

    /** ModbusIntegration 私有持有 slaveRegistry（无 getter），反射只读取账。 */
    private static ModbusSlaveRegistry slaveRegistryOf(ModbusIntegration integration) throws Exception {
        Field field = ModbusIntegration.class.getDeclaredField("slaveRegistry");
        field.setAccessible(true);
        return (ModbusSlaveRegistry) field.get(integration);
    }

    /** 字段注入（serialIntegration 在本类、loadOption 在父类，沿类层次找；本仓既有反射手法）。 */
    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException notHere) {
                // 继续向父类找
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static ModbusTcpSlaveConfig tcpConfig(int slaveId) {
        ModbusTcpSlaveConfig config = new ModbusTcpSlaveConfig(slaveId, "0.0.0.0", 15020);
        config.setCallback(mock(ModbusDataCallback.class));
        return config;
    }

    private static ModbusSerialSlaveConfig rtuConfig(int slaveId, String portName) {
        ModbusSerialSlaveConfig config = new ModbusSerialSlaveConfig(slaveId, portName, 9600, 8, 1, 0);
        config.setCallback(mock(ModbusDataCallback.class));
        return config;
    }

    /**
     * RTU 用例装配：loadOption 补坐标（registerSlave 的串口归属 owner 从本集成派生，
     * ResourceOwner.of(this) 无坐标即抛）+ spy serial（register 可计数，返回值回放
     * close 计数桩）注入 serialIntegration 字段。
     */
    private void setUpRtuHarness(String portName) throws Exception {
        IntegrationLoadOption loadOption = new IntegrationLoadOption(new URLClassLoader(new URL[0]));
        loadOption.setIntegrationInfo(new IntegrationInfo("integration-modbus", false,
                new ArrayList<>(), true, "ModbusIntegration", "com.ecat", "1.0.0", null, null));
        setField(integration, "loadOption", loadOption);

        serialSpy = Mockito.spy(new SerialIntegration());
        doAnswer(invocation -> {
            CloseCountingSerialSource source = new CloseCountingSerialSource();
            acquiredSources.add(source);
            return source;
        }).when(serialSpy).register(any(SerialInfo.class), any(ResourceOwner.class));
        setField(integration, "serialIntegration", serialSpy);
    }

    /**
     * close 计数串口桩：standalone 构造走 /dev/null（jSerialComm 拒绝不存在的描述符，
     * 本仓 RTU 测试既有路径；测试栈检测不开轮询），closePort 计数后照常释放。
     * 桩自身的口名与账目/连接键无关（connectionId 由 config 决定）。
     */
    static final class CloseCountingSerialSource extends SerialSource {
        final AtomicInteger closeCalls = new AtomicInteger();

        CloseCountingSerialSource() {
            super(new SerialInfo("/dev/null", 9600, 8, 1, 0));
        }

        @Override
        public void closePort() {
            closeCalls.incrementAndGet();
            super.closePort();
        }
    }

    /**
     * 同 connectionId + 同 slaveId 重复注册：注册中心按键去重——复用同一 server
     * 实例、callback 计数恒为 1，不产生第二条账目（config 对象与回调实例均不参与键）。
     */
    @Test
    public void duplicateRegisterDedupesToSingleServerEntry() {
        integration.registerSlave(tcpConfig(1));

        ModbusSlaveServer first = registry.getServer(CONNECTION_ID);
        assertNotNull("首次注册后账目应有 server", first);

        // 第二次注册：新 config 对象、新回调实例，键（connectionId+slaveId）相同
        integration.registerSlave(tcpConfig(1));

        assertSame("同键重复注册复用既有 server（无第二实例）", first, registry.getServer(CONNECTION_ID));
        assertEquals("同键重复注册不新增 callback 记账", 1, first.getCallbackCount());
    }

    /**
     * 摘账语义：同连接多 slaveId 时逐条摘、末条摘空即把 server 整体移出账目
     * （不残留已停摆的空 server）；移出后可重新注册且拿到全新 server 实例。
     */
    @Test
    public void unregisterLastSlaveTearsOutServerAndAllowsFreshRegister() {
        integration.registerSlave(tcpConfig(1));
        integration.registerSlave(tcpConfig(2));
        ModbusSlaveServer original = registry.getServer(CONNECTION_ID);
        assertEquals(2, original.getCallbackCount());

        // 摘一条：仍有存活 slaveId，server 保留
        integration.unregisterSlave(CONNECTION_ID, 1);
        assertNotNull("仍有 slaveId 时 server 不应移除", registry.getServer(CONNECTION_ID));
        assertEquals(1, original.getCallbackCount());

        // 摘空：server 整体出账
        integration.unregisterSlave(CONNECTION_ID, 2);
        assertNull("末 slaveId 注销后 server 应整体移出账目", registry.getServer(CONNECTION_ID));

        // 重新注册：拿到全新 server，不是复用已摘账的旧实例
        integration.registerSlave(tcpConfig(1));
        ModbusSlaveServer reRegistered = registry.getServer(CONNECTION_ID);
        assertNotNull(reRegistered);
        assertNotSame("摘账后重注册应建全新 server（不复用旧实例）", original, reRegistered);
        assertEquals(1, reRegistered.getCallbackCount());
    }

    /**
     * 注销不存在者：实现路径是记 warn 后幂等返回（不抛异常、不动既有账目）——
     * 未知 connectionId 与既有连接上的未知 slaveId 两种形态均如此。
     */
    @Test
    public void unregisterUnknownConnectionOrSlaveIdIsIdempotentNoop() {
        integration.registerSlave(tcpConfig(1));

        // 未知 connectionId：warn + return，不抛
        integration.unregisterSlave("no-such-connection:1", 1);

        // 既有连接上的未知 slaveId：摘不到 callback，账目不变、server 不误拆
        integration.unregisterSlave(CONNECTION_ID, 99);

        ModbusSlaveServer server = registry.getServer(CONNECTION_ID);
        assertNotNull("幂等注销不应影响既有注册", server);
        assertEquals("未知 slaveId 注销不动 callback 记账", 1, server.getCallbackCount());
    }

    // ==================== RTU 串口生命周期（bug-record-20260913-124500 缺陷 A/B） ====================

    /**
     * 缺陷A（重复 registerSlave 同连接泄串口账）：串口视图只在 server 首建时获取——
     * 同连接第二次注册复用既有 server，serial register 不再被调（修复前每次注册都
     * 无条件获取、新视图被静默丢弃泄漏，本用例计 2 次=红）。
     */
    @Test
    public void duplicateRegisterSlaveAcquiresSerialSourceOnlyOnce() throws Exception {
        String port = "/dev/tty-rtu-acct-dup";
        setUpRtuHarness(port);

        integration.registerSlave(rtuConfig(1, port));
        integration.registerSlave(rtuConfig(2, port));

        verify(serialSpy, times(1)).register(any(SerialInfo.class), any(ResourceOwner.class));
        assertEquals("两次注册只产生一个串口视图", 1, acquiredSources.size());
        assertNotNull("同连接复用既有 server", registry.getServer(port));
    }

    /**
     * 缺陷B（never-start server 注销双泄漏）：摘空拆 server 时无条件释放串口——
     * 从未 start 的 server 不走 stop()（!running 提前返回），注册中心拆除时兜底
     * closePort（修复前串口未关账目未销，本用例计 0 次=红）。
     */
    @Test
    public void unregisterNeverStartedRtuServerClosesSerialPort() throws Exception {
        String port = "/dev/tty-rtu-acct-neverstart";
        setUpRtuHarness(port);

        integration.registerSlave(rtuConfig(1, port));
        assertEquals("注册即获取一个串口视图", 1, acquiredSources.size());

        integration.unregisterSlave(port, 1);

        assertNull("摘空即拆 server", registry.getServer(port));
        assertEquals("拆除时无条件释放串口（never-start 无 stop 可走）",
                1, acquiredSources.get(0).closeCalls.get());
    }

    /**
     * 缺陷B 停机路径残枝（clear()/stopAll() 对 never-start server 不释放）：集成
     * onRelease 收尾走 clear→stopAll→stop()，!running 提前返回不碰串口——与 unregister
     * 摘空拆 server 同形态，停机路径同样无条件释放（本用例在修复前计 0 次=红）。
     */
    @Test
    public void clearReleasesNeverStartedRtuServerSerialPort() throws Exception {
        String port = "/dev/tty-rtu-acct-clear";
        setUpRtuHarness(port);

        integration.registerSlave(rtuConfig(1, port));
        assertEquals("注册即获取一个串口视图", 1, acquiredSources.size());

        registry.clear();

        assertNull("clear 后账目清空", registry.getServer(port));
        assertEquals("停机路径对 never-start server 同样释放串口",
                1, acquiredSources.get(0).closeCalls.get());
    }

    /**
     * 缺陷A 并发形态（putIfAbsent+败者清理）：同连接竞态注册时，输者刚获取的串口
     * 视图即取即还（closePort 自清理），胜者 server 承载全部回调——账面不留双份。
     * 确定性模拟：acquirer 执行期间经公开路径抢先完成一次注册（胜者入表）。
     */
    @Test
    public void concurrentRegistrationLoserClosesItsOwnAcquiredSource() {
        String port = "/dev/tty-rtu-acct-race";
        CloseCountingSerialSource loser = new CloseCountingSerialSource();

        registry.register(rtuConfig(1, port), () -> {
            // 模拟并发胜者：在获取串口与入表之间抢先建 server（公开路径，确定性）
            registry.register(rtuConfig(7, port), () -> null);
            return loser;
        });

        assertEquals("竞态败者即取即还（closePort 自清理）", 1, loser.closeCalls.get());
        ModbusSlaveServer server = registry.getServer(port);
        assertNotNull("胜者 server 在表", server);
        assertEquals("胜者承载双方回调", 2, server.getCallbackCount());
    }
}
