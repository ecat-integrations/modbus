/*
 * Copyright (c) 2026 ECAT Team
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ecat.integration.ModbusIntegration.Slave;

import com.ecat.integration.ModbusIntegration.ModbusSerialInfo;
import com.ecat.integration.ModbusIntegration.ModbusSerialPortWrapper;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.msg.ReadCoilsRequest;
import com.serotonin.modbus4j.msg.ReadCoilsResponse;
import com.serotonin.modbus4j.msg.ReadDiscreteInputsRequest;
import com.serotonin.modbus4j.msg.ReadDiscreteInputsResponse;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersRequest;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersResponse;
import com.serotonin.modbus4j.msg.ReadInputRegistersRequest;
import com.serotonin.modbus4j.msg.ReadInputRegistersResponse;
import com.serotonin.modbus4j.msg.WriteCoilRequest;
import com.serotonin.modbus4j.msg.WriteCoilResponse;
import com.serotonin.modbus4j.msg.WriteCoilsRequest;
import com.serotonin.modbus4j.msg.WriteCoilsResponse;
import com.serotonin.modbus4j.msg.WriteRegistersRequest;
import com.serotonin.modbus4j.msg.WriteRegistersResponse;
import com.serotonin.modbus4j.msg.WriteRegisterRequest;
import com.serotonin.modbus4j.msg.WriteRegisterResponse;

import java.util.Scanner;
import java.util.ArrayList;
import java.util.List;

/**
 * Serial RTU Master 客户端 Demo
 * 
 * <p>
 * 演示如何使用 Modbus Master 连接 Serial RTU Slave 服务，支持完整的四种数据类型
 * 读写操作和 20 个自动化测试用例。
 * 
 * <p>
 * 使用方法：
 * <ol>
 * <li>创建虚拟串口对:
 *     <pre>sudo socat -d -d pty,raw,echo=0,link=/dev/ttyV0 pty,raw,echo=0,link=/dev/ttyV1</pre>
 * </li>
 * <li>先运行 SerialRtuSlaveServerDemo (使用 /dev/ttyV0)</li>
 * <li>再运行此程序 (使用 /dev/ttyV1)</li>
 * </ol>
 * 
 * <p>
 * 支持的交互命令：
 * <ul>
 * <li>read <addr> [qty] - 读取 Holding Register (功能码 03)</li>
 * <li>write <addr> <val> ... - 写入 Holding Register (功能码 06/16)</li>
 * <li>coil <addr> [qty] - 读取 Coil (功能码 01)</li>
 * <li>coilw <addr> <0|1> ... - 写入 Coil (功能码 05/15)</li>
 * <li>di <addr> [qty] - 读取 Discrete Input (功能码 02)</li>
 * <li>ir <addr> [qty] - 读取 Input Register (功能码 04)</li>
 * <li>test - 运行 20 个自动化测试</li>
 * <li>quit - 退出程序</li>
 * </ul>
 * 
 * <p>
 * 自动化测试覆盖所有 8 个标准功能码，包含断言验证和原始数据十六进制输出。
 * 
 * @author coffee
 * @see SerialRtuSlaveServerDemo
 * @see SmartStationDeviceCallback
 */
public class SerialRtuMasterClientDemo {

    private static final int SLAVE_ID = 36;
    private static final int BAUD_RATE = 9600;
    private static final String PORT = "/dev/ttyV1";

    private static ModbusMaster master;

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("Modbus Serial RTU Master Client Demo");
        System.out.println("========================================");
        System.out.println("Port: " + PORT);
        System.out.println("Baud rate: " + BAUD_RATE);
        System.out.println("Slave ID: " + SLAVE_ID);
        System.out.println();

        try {
            startMaster();
            runInteractiveMode();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            stopAll();
        }
    }

    private static void startMaster() throws Exception {
        System.out.println("[Master] Connecting to Serial Slave...");

        ModbusSerialInfo serialInfo = new ModbusSerialInfo(
            PORT, BAUD_RATE, 8,
            ModbusSerialInfo.ONE_STOP_BIT, ModbusSerialInfo.NO_PARITY,
            3000, SLAVE_ID
        );

        ModbusFactory factory = new ModbusFactory();
        master = factory.createRtuMaster(new ModbusSerialPortWrapper(serialInfo));
        master.setTimeout(3000);
        master.init();

        System.out.println("[Master] Connected!");
        System.out.println();
    }

    private static void runInteractiveMode() throws Exception {
        System.out.println("========================================");
        System.out.println("Interactive Mode");
        System.out.println("========================================");
        System.out.println("Commands:");
        System.out.println("  read <addr>              - Read single holding register");
        System.out.println("  read <addr> <quantity>   - Read multiple holding registers");
        System.out.println("  write <addr> <value>     - Write single holding register");
        System.out.println("  write <addr> <v1> <v2>.. - Write multiple holding registers");
        System.out.println("  coil <addr>              - Read single coil");
        System.out.println("  coil <addr> <quantity>   - Read multiple coils");
        System.out.println("  coilw <addr> <0|1>       - Write single coil");
        System.out.println("  coilw <addr> <0|1> ...   - Write multiple coils");
        System.out.println("  di <addr>                - Read single discrete input");
        System.out.println("  di <addr> <quantity>     - Read multiple discrete inputs");
        System.out.println("  ir <addr>                - Read single input register");
        System.out.println("  ir <addr> <quantity>     - Read multiple input registers");
        System.out.println("  test                     - Run automated test");
        System.out.println("  quit                     - Exit program");
        System.out.println("========================================");
        System.out.println();

        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("> ");
            String line = scanner.nextLine().trim();

            if (line.isEmpty()) continue;
            if (line.equals("quit")) break;

            String[] parts = line.split("\\s+");
            String cmd = parts[0].toLowerCase();

            try {
                switch (cmd) {
                    case "read":
                        if (parts.length == 2) {
                            int addr = Integer.parseInt(parts[1]);
                            doRead(addr, 1);
                        } else if (parts.length >= 3) {
                            int addr = Integer.parseInt(parts[1]);
                            int qty = Integer.parseInt(parts[2]);
                            doRead(addr, qty);
                        } else {
                            System.out.println("Usage: read <addr> [quantity]");
                        }
                        break;

                    case "write":
                        if (parts.length == 3) {
                            int addr = Integer.parseInt(parts[1]);
                            short val = Short.parseShort(parts[2]);
                            doWriteSingle(addr, val);
                        } else if (parts.length > 3) {
                            int addr = Integer.parseInt(parts[1]);
                            short[] values = new short[parts.length - 2];
                            for (int i = 0; i < values.length; i++) {
                                values[i] = Short.parseShort(parts[i + 2]);
                            }
                            doWriteMultiple(addr, values);
                        } else {
                            System.out.println("Usage: write <addr> <value> [value2] ...");
                        }
                        break;

                    case "test":
                        runAutomatedTest();
                        break;

                    case "coil":
                        if (parts.length == 2) {
                            int addr = Integer.parseInt(parts[1]);
                            doReadCoil(addr, 1);
                        } else if (parts.length >= 3) {
                            int addr = Integer.parseInt(parts[1]);
                            int qty = Integer.parseInt(parts[2]);
                            doReadCoil(addr, qty);
                        } else {
                            System.out.println("Usage: coil <addr> [quantity]");
                        }
                        break;

                    case "coilw":
                        if (parts.length == 3) {
                            int addr = Integer.parseInt(parts[1]);
                            boolean val = parts[2].equals("1");
                            doWriteSingleCoil(addr, val);
                        } else if (parts.length > 3) {
                            int addr = Integer.parseInt(parts[1]);
                            boolean[] values = new boolean[parts.length - 2];
                            for (int i = 0; i < values.length; i++) {
                                values[i] = parts[i + 2].equals("1");
                            }
                            doWriteMultipleCoils(addr, values);
                        } else {
                            System.out.println("Usage: coilw <addr> <0|1> [0|1] ...");
                        }
                        break;

                    case "di":
                        if (parts.length == 2) {
                            int addr = Integer.parseInt(parts[1]);
                            doReadDiscreteInput(addr, 1);
                        } else if (parts.length >= 3) {
                            int addr = Integer.parseInt(parts[1]);
                            int qty = Integer.parseInt(parts[2]);
                            doReadDiscreteInput(addr, qty);
                        } else {
                            System.out.println("Usage: di <addr> [quantity]");
                        }
                        break;

                    case "ir":
                        if (parts.length == 2) {
                            int addr = Integer.parseInt(parts[1]);
                            doReadInputRegister(addr, 1);
                        } else if (parts.length >= 3) {
                            int addr = Integer.parseInt(parts[1]);
                            int qty = Integer.parseInt(parts[2]);
                            doReadInputRegister(addr, qty);
                        } else {
                            System.out.println("Usage: ir <addr> [quantity]");
                        }
                        break;

                    default:
                        System.out.println("Unknown command: " + cmd);
                }
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
        }

        scanner.close();
    }

    private static void doRead(int startAddress, int quantity) throws ModbusTransportException {
        doRead(startAddress, quantity, null);
    }

    private static short[] doRead(int startAddress, int quantity, short[] expectedValues) throws ModbusTransportException {
        System.out.println("[Master] Reading " + quantity + " register(s) from address " + startAddress);

        ReadHoldingRegistersRequest request = new ReadHoldingRegistersRequest(SLAVE_ID, startAddress, quantity);
        ReadHoldingRegistersResponse response = (ReadHoldingRegistersResponse) master.send(request);

        if (response == null) {
            System.out.println("[Master] No response received!");
            return null;
        }

        short[] data = response.getShortData();
        int byteCount = data.length * 2;

        System.out.println("[Master] Raw response (" + byteCount + " bytes, hex): " + shortsToHex(data));
        System.out.println("[Master] Received " + data.length + " register(s):");

        for (int i = 0; i < data.length; i++) {
            int unsignedValue = data[i] & 0xFFFF;
            System.out.println("  [" + (startAddress + i) + "] = " + unsignedValue + 
                             " (0x" + String.format("%04X", unsignedValue) + ")");
        }

        if (expectedValues != null) {
            assertRegistersEqual(startAddress, expectedValues, data);
        }

        return data;
    }

    private static void doWriteSingle(int address, short value) throws ModbusTransportException {
        int unsignedValue = value & 0xFFFF;
        System.out.println("[Master] Writing single register[" + address + "] = " + unsignedValue);
        System.out.println("[Master] Request data (hex): " + String.format("%04X", unsignedValue));

        WriteRegisterRequest request = new WriteRegisterRequest(SLAVE_ID, address, value);
        WriteRegisterResponse response = (WriteRegisterResponse) master.send(request);

        if (response == null) {
            System.out.println("[Master] No response received!");
            return;
        }

        System.out.println("[Master] Write successful!");
    }

    private static void doWriteMultiple(int startAddress, short[] values) throws ModbusTransportException {
        System.out.println("[Master] Writing " + values.length + " register(s) starting at " + startAddress);
        System.out.println("[Master] Request data (hex): " + shortsToHex(values));
        for (int i = 0; i < values.length; i++) {
            System.out.println("  [" + (startAddress + i) + "] = " + (values[i] & 0xFFFF));
        }

        WriteRegistersRequest request = new WriteRegistersRequest(SLAVE_ID, startAddress, values);
        WriteRegistersResponse response = (WriteRegistersResponse) master.send(request);

        if (response == null) {
            System.out.println("[Master] No response received!");
            return;
        }

        System.out.println("[Master] Write successful!");
    }

    private static boolean[] doReadCoil(int startAddress, int quantity) throws ModbusTransportException {
        return doReadCoil(startAddress, quantity, null);
    }

    private static boolean[] doReadCoil(int startAddress, int quantity, boolean[] expectedValues) throws ModbusTransportException {
        System.out.println("[Master] Reading " + quantity + " coil(s) from address " + startAddress);

        ReadCoilsRequest request = new ReadCoilsRequest(SLAVE_ID, startAddress, quantity);
        ReadCoilsResponse response = (ReadCoilsResponse) master.send(request);

        if (response == null) {
            System.out.println("[Master] No response received!");
            return null;
        }

        boolean[] data = response.getBooleanData();
        System.out.println("[Master] Raw response (bits): " + booleansToBinary(data));
        System.out.println("[Master] Received " + data.length + " coil(s):");

        for (int i = 0; i < data.length; i++) {
            System.out.println("  [" + (startAddress + i) + "] = " + (data[i] ? "ON (1)" : "OFF (0)"));
        }

        if (expectedValues != null) {
            assertCoilsEqual(startAddress, expectedValues, data);
        }

        return data;
    }

    private static void doWriteSingleCoil(int address, boolean value) throws ModbusTransportException {
        System.out.println("[Master] Writing single coil[" + address + "] = " + (value ? "ON (1)" : "OFF (0)"));

        WriteCoilRequest request = new WriteCoilRequest(SLAVE_ID, address, value);
        WriteCoilResponse response = (WriteCoilResponse) master.send(request);

        if (response == null) {
            System.out.println("[Master] No response received!");
            return;
        }

        System.out.println("[Master] Write successful!");
    }

    private static void doWriteMultipleCoils(int startAddress, boolean[] values) throws ModbusTransportException {
        System.out.println("[Master] Writing " + values.length + " coil(s) starting at " + startAddress);
        System.out.println("[Master] Request data (bits): " + booleansToBinary(values));
        for (int i = 0; i < values.length; i++) {
            System.out.println("  [" + (startAddress + i) + "] = " + (values[i] ? "ON (1)" : "OFF (0)"));
        }

        WriteCoilsRequest request = new WriteCoilsRequest(SLAVE_ID, startAddress, values);
        WriteCoilsResponse response = (WriteCoilsResponse) master.send(request);

        if (response == null) {
            System.out.println("[Master] No response received!");
            return;
        }

        System.out.println("[Master] Write successful!");
    }

    private static boolean[] doReadDiscreteInput(int startAddress, int quantity) throws ModbusTransportException {
        return doReadDiscreteInput(startAddress, quantity, null);
    }

    private static boolean[] doReadDiscreteInput(int startAddress, int quantity, boolean[] expectedValues) throws ModbusTransportException {
        System.out.println("[Master] Reading " + quantity + " discrete input(s) from address " + startAddress);

        ReadDiscreteInputsRequest request = new ReadDiscreteInputsRequest(SLAVE_ID, startAddress, quantity);
        ReadDiscreteInputsResponse response = (ReadDiscreteInputsResponse) master.send(request);

        if (response == null) {
            System.out.println("[Master] No response received!");
            return null;
        }

        boolean[] data = response.getBooleanData();
        System.out.println("[Master] Raw response (bits): " + booleansToBinary(data));
        System.out.println("[Master] Received " + data.length + " discrete input(s):");

        for (int i = 0; i < data.length; i++) {
            System.out.println("  [" + (startAddress + i) + "] = " + (data[i] ? "ON (1)" : "OFF (0)"));
        }

        if (expectedValues != null) {
            assertCoilsEqual(startAddress, expectedValues, data);
        }

        return data;
    }

    private static short[] doReadInputRegister(int startAddress, int quantity) throws ModbusTransportException {
        return doReadInputRegister(startAddress, quantity, null);
    }

    private static short[] doReadInputRegister(int startAddress, int quantity, short[] expectedValues) throws ModbusTransportException {
        System.out.println("[Master] Reading " + quantity + " input register(s) from address " + startAddress);

        ReadInputRegistersRequest request = new ReadInputRegistersRequest(SLAVE_ID, startAddress, quantity);
        ReadInputRegistersResponse response = (ReadInputRegistersResponse) master.send(request);

        if (response == null) {
            System.out.println("[Master] No response received!");
            return null;
        }

        short[] data = response.getShortData();
        int byteCount = data.length * 2;

        System.out.println("[Master] Raw response (" + byteCount + " bytes, hex): " + shortsToHex(data));
        System.out.println("[Master] Received " + data.length + " input register(s):");

        for (int i = 0; i < data.length; i++) {
            int unsignedValue = data[i] & 0xFFFF;
            System.out.println("  [" + (startAddress + i) + "] = " + unsignedValue + 
                             " (0x" + String.format("%04X", unsignedValue) + ")");
        }

        if (expectedValues != null) {
            assertRegistersEqual(startAddress, expectedValues, data);
        }

        return data;
    }

    private static String booleansToBinary(boolean[] data) {
        StringBuilder sb = new StringBuilder();
        for (boolean b : data) {
            sb.append(b ? "1" : "0");
        }
        return sb.toString();
    }

    private static void assertCoilsEqual(int startAddress, boolean[] expected, boolean[] actual) {
        if (expected == null || actual == null) {
            throw new AssertionError("Expected or actual values are null");
        }
        if (expected.length != actual.length) {
            throw new AssertionError("Length mismatch: expected " + expected.length + ", got " + actual.length);
        }
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                String msg = String.format(
                    "Coil [%d] mismatch: expected %s, got %s",
                    startAddress + i, expected[i] ? "ON (1)" : "OFF (0)", actual[i] ? "ON (1)" : "OFF (0)"
                );
                System.out.println("[ASSERT FAILED] " + msg);
                throw new AssertionError(msg);
            }
        }
        System.out.println("[ASSERT PASSED] All " + expected.length + " coil(s) verified successfully!");
    }

    private static void runAutomatedTest() throws Exception {
        List<String> testResults = new ArrayList<>();
        
        System.out.println();
        System.out.println("========================================");
        System.out.println("Running Automated Test");
        System.out.println("========================================");

        try {
            System.out.println();
            System.out.println("--- Test 1: Single register read ---");
            doRead(0, 1);
            testResults.add("Test 1: PASS");

            System.out.println();
            System.out.println("--- Test 2: Batch register read (6 registers) ---");
            doRead(0, 6);
            testResults.add("Test 2: PASS");

            System.out.println();
            System.out.println("--- Test 3: Single register write ---");
            doWriteSingle(41, (short) 450);
            testResults.add("Test 3: PASS");

            System.out.println();
            System.out.println("--- Test 4: Verify single write (expect 450 at address 41) ---");
            doRead(41, 1, new short[] { 450 });
            testResults.add("Test 4: PASS");

            System.out.println();
            System.out.println("--- Test 5: Batch register write (3 registers) ---");
            short[] writeValues = { 100, 200, 300 };
            doWriteMultiple(10, writeValues);
            testResults.add("Test 5: PASS");

            System.out.println();
            System.out.println("--- Test 6: Verify batch write (expect [100, 200, 300] at address 10-12) ---");
            doRead(10, 3, writeValues);
            testResults.add("Test 6: PASS");

            System.out.println();
            System.out.println("--- Test 7: Non-contiguous write simulation ---");
            System.out.println("Writing to address 50, 52, 55 (separate writes)");
            doWriteSingle(50, (short) 1111);
            doWriteSingle(52, (short) 2222);
            doWriteSingle(55, (short) 3333);
            testResults.add("Test 7: PASS");

            System.out.println();
            System.out.println("--- Test 8: Verify non-contiguous writes ---");
            doRead(50, 1, new short[] { 1111 });
            doRead(52, 1, new short[] { 2222 });
            doRead(55, 1, new short[] { 3333 });
            testResults.add("Test 8: PASS");

            System.out.println();
            System.out.println("--- Test 9: Single coil read ---");
            doReadCoil(0, 1);
            testResults.add("Test 9: PASS");

            System.out.println();
            System.out.println("--- Test 10: Batch coil read (4 coils) ---");
            doReadCoil(0, 4);
            testResults.add("Test 10: PASS");

            System.out.println();
            System.out.println("--- Test 11: Single coil write (address 60 -> ON) ---");
            doWriteSingleCoil(60, true);
            testResults.add("Test 11: PASS");

            System.out.println();
            System.out.println("--- Test 12: Verify single coil write (expect ON at address 60) ---");
            doReadCoil(60, 1, new boolean[] { true });
            testResults.add("Test 12: PASS");

            System.out.println();
            System.out.println("--- Test 13: Batch coil write (address 65-68 -> [ON, OFF, ON, OFF]) ---");
            boolean[] coilValues = { true, false, true, false };
            doWriteMultipleCoils(65, coilValues);
            testResults.add("Test 13: PASS");

            System.out.println();
            System.out.println("--- Test 14: Verify batch coil write (expect [ON, OFF, ON, OFF] at address 65-68) ---");
            doReadCoil(65, 4, coilValues);
            testResults.add("Test 14: PASS");

            System.out.println();
            System.out.println("--- Test 15: Single discrete input read ---");
            doReadDiscreteInput(0, 1);
            testResults.add("Test 15: PASS");

            System.out.println();
            System.out.println("--- Test 16: Batch discrete input read (4 inputs) ---");
            doReadDiscreteInput(0, 4);
            testResults.add("Test 16: PASS");

            System.out.println();
            System.out.println("--- Test 17: Single input register read ---");
            doReadInputRegister(0, 1);
            testResults.add("Test 17: PASS");

            System.out.println();
            System.out.println("--- Test 18: Batch input register read (6 registers) ---");
            doReadInputRegister(0, 6);
            testResults.add("Test 18: PASS");

            System.out.println();
            System.out.println("--- Test 19: Verify input register matches holding register ---");
            short[] holdingData = doRead(70, 3, null);
            doReadInputRegister(70, 3, holdingData);
            testResults.add("Test 19: PASS");

            System.out.println();
            System.out.println("--- Test 20: Coil OFF write and verify ---");
            doWriteSingleCoil(60, false);
            doReadCoil(60, 1, new boolean[] { false });
            testResults.add("Test 20: PASS");

        } catch (AssertionError e) {
            System.out.println();
            System.out.println("[TEST FAILED] " + e.getMessage());
            throw e;
        }

        System.out.println();
        System.out.println("========================================");
        System.out.println("Automated Test Complete");
        System.out.println("========================================");
        System.out.println("Results:");
        for (String result : testResults) {
            System.out.println("  " + result);
        }
        System.out.println();
        System.out.println("All " + testResults.size() + " tests PASSED!");
    }

    private static String shortsToHex(short[] data) {
        StringBuilder sb = new StringBuilder();
        for (short s : data) {
            int highByte = (s >> 8) & 0xFF;
            int lowByte = s & 0xFF;
            sb.append(String.format("%02X %02X ", highByte, lowByte));
        }
        return sb.toString().trim();
    }

    private static void assertRegistersEqual(int startAddress, short[] expected, short[] actual) {
        if (expected == null || actual == null) {
            throw new AssertionError("Expected or actual values are null");
        }
        if (expected.length != actual.length) {
            throw new AssertionError("Length mismatch: expected " + expected.length + ", got " + actual.length);
        }
        for (int i = 0; i < expected.length; i++) {
            int expVal = expected[i] & 0xFFFF;
            int actVal = actual[i] & 0xFFFF;
            if (expVal != actVal) {
                String msg = String.format(
                    "Register [%d] mismatch: expected %d (0x%04X), got %d (0x%04X)",
                    startAddress + i, expVal, expVal, actVal, actVal
                );
                System.out.println("[ASSERT FAILED] " + msg);
                throw new AssertionError(msg);
            }
        }
        System.out.println("[ASSERT PASSED] All " + expected.length + " register(s) verified successfully!");
    }

    private static void stopAll() {
        System.out.println();
        System.out.println("Stopping Master...");

        if (master != null) {
            master.destroy();
            System.out.println("[Master] Disconnected");
        }

        System.out.println("Done.");
    }
}
