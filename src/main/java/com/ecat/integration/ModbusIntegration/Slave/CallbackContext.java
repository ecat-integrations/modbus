/*
 * Copyright (c) 2026 ECAT Team
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ecat.integration.ModbusIntegration.Slave;

import lombok.Getter;

/**
 * Modbus 回调上下文基类
 * 
 * <p>
 * 提供读写操作的公共上下文信息，包括从站ID、起始地址和数量。
 * 子类可扩展特定操作的上下文数据。
 * 
 * @author coffee
 */
@Getter
public abstract class CallbackContext {
    protected final int slaveId;
    protected final int startAddress;
    protected final int quantity;

    protected CallbackContext(int slaveId, int startAddress, int quantity) {
        this.slaveId = slaveId;
        this.startAddress = startAddress;
        this.quantity = quantity;
    }
}
