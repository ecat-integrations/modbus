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

import java.lang.reflect.Method;
import java.util.List;

import org.junit.Test;

import com.ecat.core.CommTrace.CommTraceBuffer;
import com.ecat.core.CommTrace.CommTraceDirection;
import com.ecat.core.CommTrace.CommTraceEvent;
import com.ecat.core.CommTrace.CommTraceFilter;
import com.ecat.core.CommTrace.CommTraceTransport;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.msg.ModbusRequest;
import com.serotonin.modbus4j.msg.ModbusResponse;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersRequest;

/**
 * ModbusSource 通讯帧埋点测试：sendTraced 事务层（mock ModbusMaster 捕获 TX/RX 配对）
 * 与 encodeMessage（modbus4j 消息 → PDU+slaveId 编码字节）。
 */
public class ModbusSourceCommTraceTest {

    /** 可直接 new 的测试桩源（构造函数 protected，同包可达）。 */
    static class TestModbusSource extends ModbusSource {
        TestModbusSource(ModbusInfo info) {
            super(info, 1, 100, true, false);
        }

        void setMaster(ModbusMaster master) {
            try {
                java.lang.reflect.Field f = ModbusSource.class.getDeclaredField("modbusMaster");
                f.setAccessible(true);
                f.set(this, master);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Test
    public void encodeMessageProducesSlaveIdPduBytes() throws Exception {
        ReadHoldingRegistersRequest request = new ReadHoldingRegistersRequest(1, 0, 2);
        assertEquals("slaveId+FC+start+count 编码", "01 03 00 00 00 02",
                hex(ModbusSource.encodeMessage(request)));
    }

    @Test
    public void sendTracedCapturesTxRxPairWithSameTxnId() throws Exception {
        ModbusSerialInfo info = new ModbusSerialInfo("/dev/tty-commtrace-mb", 9600, 8, 1, 0, 500, 1);
        TestModbusSource source = new TestModbusSource(info);
        // mock master：send 直接返回固定响应（RTU 响应帧 slaveId=1, FC=03, byteCount=4, 2 寄存器）
        ModbusMaster master = new StubMaster() {
            {
                initialized = true;
            }
            @Override
            public ModbusResponse sendImpl(ModbusRequest request) {
                // modbus4j 响应构造器全部包级保护，用 mockito stub 写行为（编码走 write 模板方法，stub 默认空帧）
                // 响应构造器全包级保护，mockito-inline mock 具体响应类（write 模板默认空操作→编码空帧）
                return org.mockito.Mockito.mock(
                        com.serotonin.modbus4j.msg.ReadHoldingRegistersResponse.class);
            }
        };
        source.setMaster(master);
        long since = CommTraceBuffer.instance().latestSeq();

        Object response = invokeReadHolding(source, 1, 0, 2);

        assertEquals("响应透传", true,
                response instanceof com.serotonin.modbus4j.msg.ReadHoldingRegistersResponse);
        List<CommTraceEvent> events = CommTraceBuffer.instance().query(
                new CommTraceFilter(CommTraceTransport.SERIAL, "/dev/tty-commtrace-mb", null, null, null),
                10, since);
        assertEquals("TX+RX 各 1 帧", 2, events.size());
        CommTraceEvent tx = events.get(0);
        CommTraceEvent rx = events.get(1);
        assertEquals(CommTraceDirection.TX, tx.getDirection());
        assertEquals(CommTraceDirection.RX, rx.getDirection());
        assertEquals("txnId 配对一致", tx.getTxnId(), rx.getTxnId());
        assertEquals("01 03 00 00 00 02", tx.renderHex());
        assertEquals("RX 携带响应耗时", Double.valueOf(rx.getDurationMillis()) != null, true);
    }

    @Test
    public void sendFailureCountsChannelErrorNoRx() throws Exception {
        ModbusSerialInfo info = new ModbusSerialInfo("/dev/tty-commtrace-mb-err", 9600, 8, 1, 0, 500, 1);
        TestModbusSource source = new TestModbusSource(info);
        source.setMaster(new StubMaster() {
            {
                initialized = true;
            }
            @Override
            public ModbusResponse sendImpl(ModbusRequest request) throws ModbusTransportException {
                throw new ModbusTransportException("io");
            }
        });
        long since = CommTraceBuffer.instance().latestSeq();

        Object result = invokeReadHolding(source, 1, 0, 2);

        assertEquals("异常路径吞 ModbusTransportException 返 null（原有语义）", null, result);
        List<CommTraceEvent> events = CommTraceBuffer.instance().query(
                new CommTraceFilter(CommTraceTransport.SERIAL, "/dev/tty-commtrace-mb-err", null, null, null),
                10, since);
        assertEquals("只 TX 1 帧", 1, events.size());
        assertEquals(CommTraceDirection.TX, events.get(0).getDirection());
    }

    // ===== helpers =====

    private static Object invokeReadHolding(ModbusSource source, int slaveId, int start, int count)
            throws Exception {
        Method m = ModbusSource.class.getDeclaredMethod("readHoldingRegistersWithSlaveId",
                Integer.class, int.class, int.class);
        m.setAccessible(true);
        return ((java.util.concurrent.Future<?>) m.invoke(source, slaveId, start, count))
                .get(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 最小桩 master（ModbusMaster 是抽象类，send 为 final 模板方法委托 sendImpl）。 */
    abstract static class StubMaster extends ModbusMaster {
        @Override public void init() {}
        @Override public void destroy() {}
    }
}
