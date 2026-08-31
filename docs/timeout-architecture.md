# Modbus 超时架构详解

> 从 README 移入的设计说明（2026-08-31 README 整改，内容原样保留）。两种超时的配置入口摘要见 README「核心配置」节；若本文与 src/main 现状冲突，以源码为准。

## 1. 事务超时（Transaction Timeout）

**含义**：等待 Modbus 从站设备返回响应数据包的最长时间。从 ecat 发送请求帧开始计时，到收到完整响应帧为止。

**配置位置**：
- **TCP 模式**：`ModbusTcpCommConfigSchema` 的 `timeout` 字段（可选，默认 2000ms，范围 100-30000ms）
- **RTU 模式**：`SerialCommConfigSchema` 的 `timeout` 字段（可选，默认 500ms，范围 100-60000ms）

**传递链路**：
```
TCP: ConfigEntry YAML → comm_settings.timeout → ModbusTcpInfo.timeout → modbusMaster.setTimeout()
RTU: ConfigEntry YAML → serial_settings.timeout → ModbusSerialInfo.timeout → modbusMaster.setTimeout()
```

**实际效果**：`modbusMaster.setTimeout()` 控制的是 Modbus4J 库中每次 `send(request)` 调用的响应等待时间。超过此时间未收到响应，Modbus4J 抛出 `ModbusTransportException`。

**RTU 与 TCP 的差异**：
- RTU 是串行总线半双工通信，主站发送请求后同一总线上只能等待该从站回复，因此事务超时 = 等待设备响应的最长时间。默认 500ms 适合短距离 RS-485 总线；如果波特率低（如 2400）或设备响应慢，需适当增大。
- TCP 是全双工网络通信，但 Modbus4J 的 TCP 实现仍然是同步请求-响应模型。默认 2000ms 适合局域网内设备；跨网段或互联网场景可能需要更大值。

**连接共享影响**：`ModbusSource` 按 `ip:port`（TCP）或 `portName`（RTU）共享，同一连接上的所有设备共用一个 `ModbusMaster` 实例。第一个创建连接的设备设置 `setTimeout()`，后续共享连接的设备使用相同的超时值。同一连接下的设备通常类型相同、响应速度一致，因此不会产生问题。

## 2. 锁等待超时（Lock Wait Timeout）

**含义**：当多个设备共享同一个 Modbus 连接时，排队等待独占访问锁的最长时间。一个设备正在执行 Modbus 事务期间，其他设备必须等待前一个设备完成并释放锁。

**配置位置**：`ModbusIntegration` 集成配置的 `wait_timeout` 字段（可选，默认 2000ms，范围 1000-10000ms）

**传递链路**：
```
integration-modbus.yml → wait_timeout → ModbusIntegration.waitTimeoutMs → ModbusSource.acquire(waitTimeoutMs)
```

**实际效果**：`ModbusSource.acquire()` 使用 `ReentrantLock` + `Condition.await(timeout)` 实现 FIFO 等待队列。如果等待超时仍未获得锁，返回 `null`，`ModbusTransactionStrategy` 将不会执行操作。该超时是**全局配置**，作用于所有 ModbusSource 实例。

## 3. 两种超时的协作关系

一次 Modbus 操作的完整耗时 = 锁等待时间 + 事务执行时间：

```
设备A调用 executeWithLambda()
  ├─ acquire() 等待锁 ← 锁等待超时控制（wait_timeout）
  │   ├─ 设备B正在执行事务...
  │   └─ 设备B完成，释放锁
  ├─ 获得锁，发送 Modbus 请求
  └─ 等待设备响应   ← 事务超时控制（timeout）
      ├─ 收到响应，处理数据
      └─ release() 释放锁，唤醒下一个等待者
```

- 如果锁等待超时，操作直接失败（设备未获得执行机会）
- 如果锁等待成功但事务超时，Modbus4J 抛出 `ModbusTransportException`（设备响应慢或断线）

## 4. 超时配置参考值

| 场景 | 事务超时(TCP) | 事务超时(RTU) | 锁等待超时 |
|------|-------------|-------------|-----------|
| 局域网内快速设备 | 1000ms | 500ms | 2000ms |
| 局域网一般设备 | 2000ms（默认） | 1000ms | 3000ms |
| 跨网段/互联网 | 5000ms | - | 5000ms |
| 低波特率总线(2400) | - | 2000ms | 3000ms |
| 多设备高并发 | 2000ms | 1000ms | 5000ms |
