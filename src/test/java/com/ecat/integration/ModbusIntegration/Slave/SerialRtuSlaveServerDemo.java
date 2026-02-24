/*
 * Copyright (c) 2026 ECAT Team
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ecat.integration.ModbusIntegration.Slave;

import java.util.Scanner;

/**
 * Serial RTU Slave 服务端 Demo
 * 
 * <p>
 * 演示如何启动 Modbus Serial RTU Slave 服务，使用 {@link SmartStationDeviceCallback}
 * 模拟智慧站房设备响应外部 Master 的读写请求。
 * 
 * <p>
 * 使用方法：
 * <ol>
 * <li>创建虚拟串口对:
 *     <pre>sudo socat -d -d pty,raw,echo=0,link=/dev/ttyV0 pty,raw,echo=0,link=/dev/ttyV1</pre>
 * </li>
 * <li>先运行此程序 (使用 /dev/ttyV0)</li>
 * <li>再运行 SerialRtuMasterClientDemo (使用 /dev/ttyV1)</li>
 * </ol>
 * 
 * <p>
 * 交互命令：
 * <ul>
 * <li>status - 显示当前状态</li>
 * <li>reset - 重置读写计数器</li>
 * <li>quit - 退出程序</li>
 * </ul>
 * 
 * @author coffee
 * @see SmartStationDeviceCallback
 * @see SerialRtuMasterClientDemo
 */
public class SerialRtuSlaveServerDemo {

    private static final int SLAVE_ID = 36;
    private static final int BAUD_RATE = 9600;
    private static final String PORT = "/dev/ttyV0";

    private static ModbusSlaveServer slaveServer;
    private static SmartStationDeviceCallback callback;

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("Modbus Serial RTU Slave Server Demo");
        System.out.println("========================================");
        System.out.println("Port: " + PORT);
        System.out.println("Baud rate: " + BAUD_RATE);
        System.out.println("Slave ID: " + SLAVE_ID);
        System.out.println();

        try {
            startSlave();
            runInteractiveMode();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            stopAll();
        }
    }

    private static void startSlave() throws Exception {
        System.out.println("[Slave] Starting Modbus Serial Slave...");

        callback = new SmartStationDeviceCallback();

        ModbusSerialSlaveConfig config = new ModbusSerialSlaveConfig(
            SLAVE_ID, PORT, BAUD_RATE,
            8, ModbusSerialSlaveConfig.ONE_STOP_BIT, ModbusSerialSlaveConfig.NO_PARITY
        );
        config.setCallback(callback);

        slaveServer = new ModbusSlaveServer(config);
        slaveServer.registerCallback(SLAVE_ID, callback);
        slaveServer.start();

        System.out.println("[Slave] Serial Slave started!");
        System.out.println("[Slave] Waiting for Master connections...");
        System.out.println();
    }

    private static void runInteractiveMode() throws Exception {
        System.out.println("========================================");
        System.out.println("Slave Monitor Mode");
        System.out.println("========================================");
        System.out.println("Commands:");
        System.out.println("  stats              - Show read/write statistics");
        System.out.println("  reset              - Reset statistics");
        System.out.println("  set <addr> <value> - Set register value");
        System.out.println("  get <addr>         - Get register value");
        System.out.println("  quit               - Exit program");
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
                    case "stats":
                        showStats();
                        break;

                    case "reset":
                        callback.resetCounters();
                        System.out.println("Statistics reset.");
                        break;

                    case "set":
                        if (parts.length >= 3) {
                            int addr = Integer.parseInt(parts[1]);
                            short val = Short.parseShort(parts[2]);
                            callback.setRegister(addr, val);
                            System.out.println("Register[" + addr + "] = " + val);
                        } else {
                            System.out.println("Usage: set <address> <value>");
                        }
                        break;

                    case "get":
                        if (parts.length >= 2) {
                            int addr = Integer.parseInt(parts[1]);
                            short val = callback.getRegister(addr);
                            System.out.println("Register[" + addr + "] = " + (val & 0xFFFF));
                        } else {
                            System.out.println("Usage: get <address>");
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

    private static void showStats() {
        System.out.println("========================================");
        System.out.println("Slave Statistics");
        System.out.println("========================================");
        System.out.println("Read requests:  " + callback.getReadCount());
        System.out.println("Write requests: " + callback.getWriteCount());
        System.out.println("========================================");

        System.out.println();
        System.out.println("Sample register values:");
        System.out.println("  [0] A相电压: " + (callback.getRegister(0) & 0xFFFF) + "V");
        System.out.println("  [3] A相电流: " + ((callback.getRegister(3) & 0xFFFF) / 10.0) + "A");
        System.out.println("  [34] 温度: " + callback.getRegister(34) + "℃");
        System.out.println("  [41] 加热设定: " + ((callback.getRegister(41) & 0xFFFF) / 10.0) + "℃");
    }

    private static void stopAll() {
        System.out.println();
        System.out.println("Stopping Slave...");

        if (slaveServer != null) {
            slaveServer.stop();
            System.out.println("[Slave] Stopped");
        }

        System.out.println("Done.");
    }
}
