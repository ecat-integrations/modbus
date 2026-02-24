/*
 * Copyright (c) 2026 ECAT Team
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ecat.integration.ModbusIntegration.Slave;

import lombok.Getter;

/**
 * 写入操作回调上下文
 * 
 * <p>
 * 封装 Modbus 写入操作的上下文信息，包括从站ID、起始地址、写入数量和写入数据。
 * 用于批量写入操作时传递给回调方法。
 * 
 * @author coffee
 */
@Getter
public class WriteCallbackContext extends CallbackContext {
    private final byte[] writeData;

    public WriteCallbackContext(int slaveId, int startAddress, int quantity, byte[] writeData) {
        super(slaveId, startAddress, quantity);
        this.writeData = writeData;
    }
}
