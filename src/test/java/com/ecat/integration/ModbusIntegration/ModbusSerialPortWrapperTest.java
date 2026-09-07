package com.ecat.integration.ModbusIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.fazecast.jSerialComm.SerialPortIOException;
import com.serotonin.modbus4j.sero.messaging.DataConsumer;
import com.serotonin.modbus4j.sero.messaging.InputStreamListener;

/**
 * 串口断口异常翻译契约测试（bug-record-20260907-093000 磁盘刷爆事故根因修复）。
 *
 * <p>modbus4j InputStreamListener.run() 捕获 IOException 后，仅当消息 equals "Stream closed."
 * （原配 serotonin 串口库文案）或 contains "nativeavailable" 才退出循环，否则按临时错误
 * 每 50ms 重试。jSerialComm 断口抛 SerialPortIOException 文案不匹配白名单 → 对端永久离线时
 * 循环永续（40 口 × 20 次/秒异常风暴）。修复 = 在流边界把该异常翻译成白名单文案。</p>
 *
 * <ul>
 *   <li>用例①②：翻译契约——消息逐字 equals 白名单文案，cause 保留原始异常；</li>
 *   <li>用例③：正常流零侵扰——纯委托不改行为；</li>
 *   <li>用例④：端到端锁死事故形态——真实 modbus4j listener 消费翻译流，首颗异常即退出
 *       （handleIOException 恰 1 次，线程不再 50ms 空转）。</li>
 * </ul>
 *
 * @author coffee
 */
public class ModbusSerialPortWrapperTest {

    /** 用例①：available() 的 SerialPortIOException 必须翻译成白名单文案并保留 cause。 */
    @Test
    public void availableTranslatesSerialPortIOExceptionToWhitelistedMessage() throws Exception {
        InputStream translated = new ModbusSerialPortWrapper.JSerialCommExitTranslationInputStream(new BrokenPortStream());
        try {
            translated.available();
            fail("断口流的 available() 应抛出翻译后的 IOException");
        } catch (IOException e) {
            assertEquals("Stream closed.", e.getMessage());
            assertTrue("cause 须保留原始 SerialPortIOException 供排障",
                    e.getCause() instanceof SerialPortIOException);
        }
    }

    /** 用例②：单字节 read() 的翻译契约与用例①一致。 */
    @Test
    public void readTranslatesSerialPortIOExceptionToWhitelistedMessage() throws Exception {
        InputStream translated = new ModbusSerialPortWrapper.JSerialCommExitTranslationInputStream(new BrokenPortStream());
        try {
            translated.read();
            fail("断口流的 read() 应抛出翻译后的 IOException");
        } catch (IOException e) {
            assertEquals("Stream closed.", e.getMessage());
            assertTrue("cause 须保留原始 SerialPortIOException 供排障",
                    e.getCause() instanceof SerialPortIOException);
        }
    }

    /** 用例③：正常流零侵扰——翻译层是纯委托，不改 available/read 语义。 */
    @Test
    public void normalStreamPassesThroughUnchanged() throws Exception {
        InputStream translated = new ModbusSerialPortWrapper.JSerialCommExitTranslationInputStream(
                new ByteArrayInputStream(new byte[]{1, 2, 3}));
        assertEquals(3, translated.available());
        assertEquals(1, translated.read());
    }

    /**
     * 用例④（端到端，锁死事故形态）：真实 modbus4j InputStreamListener 消费「翻译流(断口假流)」，
     * 首颗翻译异常命中白名单 → running=false 自行退出。
     * 未修复时：异常文案不匹配 → 50ms 循环永续 → join 超时后线程仍活、handleIOException ~20 次/秒。
     */
    @Test
    public void realListenerExitsOnFirstTranslatedException() throws Exception {
        IOExceptionCounterConsumer consumer = new IOExceptionCounterConsumer();
        InputStreamListener listener = new InputStreamListener(
                new ModbusSerialPortWrapper.JSerialCommExitTranslationInputStream(new BrokenPortStream()),
                consumer);

        Thread listenerThread = new Thread(listener, "isl-exit-contract-test");
        listenerThread.start();
        listenerThread.join(2000);

        assertFalse("listener 线程应在首颗翻译异常后退出（白名单命中）", listenerThread.isAlive());
        assertEquals("handleIOException 应恰被调 1 次（未翻译时约 20 次/秒）",
                1, consumer.count.get());
        assertEquals("Stream closed.", consumer.lastMessage);
    }

    /** 模拟 jSerialComm 断口流：任何读/探测均抛断口异常（文案与真实库逐字一致）。 */
    private static final class BrokenPortStream extends InputStream {
        @Override
        public int available() throws IOException {
            throw new SerialPortIOException("This port appears to have been shutdown or disconnected.");
        }

        @Override
        public int read() throws IOException {
            throw new SerialPortIOException("This port appears to have been shutdown or disconnected.");
        }
    }

    /** 最小 DataConsumer 桩：只统计 handleIOException 次数与最后收到的消息。 */
    private static final class IOExceptionCounterConsumer implements DataConsumer {
        final AtomicInteger count = new AtomicInteger();
        volatile String lastMessage;

        @Override
        public void data(byte[] bytes, int length) {
            // 断口流无数据回调，无需实现
        }

        @Override
        public void handleIOException(IOException e) {
            count.incrementAndGet();
            lastMessage = e.getMessage();
        }
    }
}
