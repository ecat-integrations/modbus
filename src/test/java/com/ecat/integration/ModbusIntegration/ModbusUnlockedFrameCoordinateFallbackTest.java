/*
 * Copyright (c) 2026 ECAT Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ecat.integration.ModbusIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;

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
 * 无锁路径帧归因的源注册兜底（bug-record-20260913-111500 回归）：裸 dispatchIo 读事务
 * 不经源锁（lockAcquireOwner 恒 null），归因兜底=本源注册账本的坐标级 owner 投影——
 * 不再依赖提交线程 MDC（RECONFIGURE 热加载在 REST 线程重新调度轮询后 MDC 快照丢
 * coordinate，帧变无主）。
 *
 * <p>维度契约：兜底投影恰好坐标级（coordinate 恢复、deviceId/deviceName 维持 null）——
 * 无锁路径本无逐笔身份，注册者层级无论 ENTRY/DEVICE 一律折到坐标，不引入 MDC 时代
 * 不存在的维度；有锁路径 lockAcquireOwner 权威不变（胜出兜底）；LEGACY/无注册维持
 * MDC 既有次序。
 */
public class ModbusUnlockedFrameCoordinateFallbackTest {

    private static final String COORDINATE = "com.ecat:integration-modbus-generic-device";

    /** 最小桩 master（ModbusMaster 抽象类，send 为 final 模板方法委托 sendImpl）。 */
    abstract static class StubMaster extends ModbusMaster {
        @Override public void init() {}
        @Override public void destroy() {}
    }

    /** 可发帧的裸源（同 ModbusLockOwnerTest 形态：stub master + 反射注入）。 */
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

    private static List<CommTraceEvent> queryFrames(String portName, long since) {
        return CommTraceBuffer.instance().query(
                new CommTraceFilter(CommTraceTransport.SERIAL, portName, null, null, null), 10, since);
    }

    /** 复现 111500 形态：无锁裸派发读 + 提交线程 MDC 为空（REST 线程重调度后的快照形态）。 */
    private static List<CommTraceEvent> unlockedRead(ModbusSource source, String portName) throws Exception {
        MDC.clear(); // 提交线程 MDC 为空：MDC 通道不供归因，只剩源注册兜底可依赖
        long since = CommTraceBuffer.instance().latestSeq();
        source.readHoldingRegistersWithSlaveId(1, 0, 2).get(10, TimeUnit.SECONDS);
        return queryFrames(portName, since);
    }

    @Before
    public void setUp() {
        MDC.clear();
    }

    @After
    public void tearDown() {
        MDC.clear();
    }

    // ==================== 兜底恢复 coordinate（111500 主回归） ====================

    /**
     * mgd 形态（ENTRY 层注册 + 无锁裸读 + 提交线程 MDC 空）：TX/RX 帧归属注册者坐标——
     * RECONFIGURE 热加载后归因不再丢（修复前 coordinate=null，本用例红）。
     */
    @Test
    public void unlockedReadFallsBackToRegisteredOwnerCoordinate() throws Exception {
        ModbusSource source = tracedSource("/dev/tty-mb-regfb-entry");
        source.registerIntegration(ResourceOwner.entry(COORDINATE, "module-90733a58"));

        List<CommTraceEvent> events = unlockedRead(source, "/dev/tty-mb-regfb-entry");

        assertEquals("读事务 TX+RX 各 1 帧", 2, events.size());
        assertEquals(CommTraceDirection.TX, events.get(0).getDirection());
        assertEquals(CommTraceDirection.RX, events.get(1).getDirection());
        assertEquals("TX 兜底归属注册者坐标", COORDINATE, events.get(0).getCoordinate());
        assertEquals("RX 同一副本归因", COORDINATE, events.get(1).getCoordinate());
    }

    /**
     * 维度契约：注册者是 DEVICE 层时兜底也只投坐标——deviceId/deviceName 不随兜底出现
     * （无锁路径无逐笔身份，坐标级投影是上限；修复前本用例 coordinate=null 红）。
     */
    @Test
    public void deviceLevelRegistrantProjectsCoordinateOnly() throws Exception {
        ModbusSource source = tracedSource("/dev/tty-mb-regfb-device");
        source.registerIntegration(ResourceOwner.device(COORDINATE, "entry-1", "dev-1"));

        List<CommTraceEvent> events = unlockedRead(source, "/dev/tty-mb-regfb-device");

        assertEquals(2, events.size());
        assertEquals("DEVICE 层注册者折到坐标级投影", COORDINATE, events.get(0).getCoordinate());
        assertNull("兜底不引入 deviceId 维度", events.get(0).getDeviceId());
        assertNull("兜底不引入 deviceName 维度", events.get(0).getDeviceName());
    }

    /** 多注册者（mgd 同连接多模块形态）：确定性取首个注册者，坐标一致投影不受选取影响。 */
    @Test
    public void multiRegistrantSameCoordinateDeterministicProjection() throws Exception {
        ModbusSource source = tracedSource("/dev/tty-mb-regfb-multi");
        source.registerIntegration(ResourceOwner.entry(COORDINATE, "module-1"));
        source.registerIntegration(ResourceOwner.entry(COORDINATE, "module-2"));

        List<CommTraceEvent> events = unlockedRead(source, "/dev/tty-mb-regfb-multi");

        assertEquals(2, events.size());
        assertEquals("多注册者同坐标：任取首位投影一致", COORDINATE, events.get(0).getCoordinate());
        assertNull(events.get(0).getDeviceId());
    }

    // ==================== 契约边界（修复前后均应绿：不变项） ====================

    /** 有锁路径权威不变：持锁 owner 胜出兜底（帧归属逐笔持锁者，非注册账本）。 */
    @Test
    public void lockHolderWinsOverRegisteredFallback() throws Exception {
        ModbusSource source = tracedSource("/dev/tty-mb-regfb-lock");
        source.registerIntegration(ResourceOwner.entry(COORDINATE, "module-1"));
        MDC.clear();
        long since = CommTraceBuffer.instance().latestSeq();

        String key = source.acquire(ResourceOwner.device(COORDINATE, "entry-1", "dev-holder"));
        assertNotNull(key);
        source.readHoldingRegistersWithSlaveId(1, 0, 2).get(10, TimeUnit.SECONDS);
        source.release(key);

        List<CommTraceEvent> events = queryFrames("/dev/tty-mb-regfb-lock", since);
        assertEquals(2, events.size());
        assertEquals("持锁 owner 权威：deviceId=持锁设备", "dev-holder", events.get(0).getDeviceId());
        assertEquals(COORDINATE, events.get(0).getCoordinate());
    }

    /** LEGACY 注册维持 MDC 路径：无类型化注册者不注入兜底（MDC 空=如实无主）。 */
    @Test
    public void legacyOnlyRegistrationKeepsMdcAttribution() throws Exception {
        ModbusSource source = tracedSource("/dev/tty-mb-regfb-legacy");
        source.registerIntegration(ResourceOwner.legacy("legacy-identity"));

        List<CommTraceEvent> events = unlockedRead(source, "/dev/tty-mb-regfb-legacy");

        assertEquals(2, events.size());
        assertNull("LEGACY 不注入兜底（MDC 通道空，如实无主）", events.get(0).getCoordinate());
        assertNull(events.get(0).getDeviceId());
    }

    /** 无任何注册维持 MDC 路径（与 ModbusLockOwnerTest 既有现状用例同一语义，本文件独立锁死）。 */
    @Test
    public void unregisteredSourceKeepsMdcAttribution() throws Exception {
        ModbusSource source = tracedSource("/dev/tty-mb-regfb-none");

        List<CommTraceEvent> events = unlockedRead(source, "/dev/tty-mb-regfb-none");

        assertEquals(2, events.size());
        assertNull("无注册不注入兜底", events.get(0).getCoordinate());
    }
}
