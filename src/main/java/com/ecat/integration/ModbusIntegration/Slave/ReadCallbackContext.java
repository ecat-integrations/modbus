/*
 * Copyright (c) 2026 ECAT Team
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ecat.integration.ModbusIntegration.Slave;

/**
 * 读取操作回调上下文
 * 
 * <p>
 * 封装 Modbus 读取操作的上下文信息，包括从站ID、起始地址和读取数量。
 * 用于批量读取操作时传递给回调方法。
 * 
 * @author coffee
 */
public class ReadCallbackContext extends CallbackContext {

    public ReadCallbackContext(int slaveId, int startAddress, int quantity) {
        super(slaveId, startAddress, quantity);
    }
}
