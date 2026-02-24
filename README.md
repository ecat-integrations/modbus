# ECAT Modbus 集成模块

## 概述

ECAT Modbus 集成模块为所有 ecat-integrations 提供了完整的 Modbus TCP/RTU 访问能力。该模块基于 Modbus4J 库构建，支持多种协议类型，并提供了高效的连接管理和并发控制机制。

## 核心特性

### 🔧 协议支持
- **Modbus TCP**: 标准 Modbus over TCP/IP 协议
- **Modbus RTU/ASCII**: 串行通信协议（RS-232/RS-485）
- **Modbus RTU over TCP**: 在 TCP 连接上传输 RTU 帧

### 🔄 连接管理
- **连接复用**: 同一物理连接可被多个逻辑设备共享
- **自动重连**: 支持断线自动重连机制
- **资源池化**: TCP 和串行连接分别管理，提高资源利用率

### 🔒 并发控制
- **锁机制**: 内置锁机制确保并发访问安全
- **等待队列**: 支持可配置的等待队列长度
- **超时控制**: 灵活的超时设置，避免无限等待

### ⚙️ 配置灵活
- **动态配置**: 支持运行时配置修改
- **参数验证**: 配置参数自动验证，确保参数有效性
- **默认值**: 提供合理的默认配置值

### 🖥️ Slave 服务
- **回调模式**: 通过回调接口处理外部 Master 的读写请求
- **协议完整**: 支持全部 8 个标准功能码（01-04 读，05-06 单写，15-16 批量写）
- **双模式**: 同时支持 TCP Slave 和 Serial RTU Slave
- **设计文档**: [Modbus Slave 设计文档](docs/plans/2026-02-24-modbus-slave-design.md)

## 架构设计

### 核心组件

#### 1. ModbusIntegration
主要的集成管理类，负责：
- Modbus 资源的注册和管理
- TCP 和串行连接池的维护
- 配置加载和验证
- 生命周期管理（初始化、启动、暂停、释放）

#### 2. ModbusSource
底层的 Modbus 连接抽象类，提供：
- 完整的 Modbus 功能支持（读/写线圈、寄存器等）
- 异步操作支持（基于 CompletableFuture）
- 锁管理和并发控制
- 连接状态监控

#### 3. DeviceSpecificModbusSource
设备特定的 ModbusSource 包装器，解决：
- 多设备共享同一物理连接时的 slaveId 冲突
- 设备级别的隔离和安全性
- 统一的设备访问接口

#### 4. ModbusTransactionStrategy
事务策略类，提供安全的 Modbus 操作执行机制：
- **自动锁管理**: 自动获取和释放锁，确保并发安全
- **异常处理**: 内置异常处理机制，确保锁的正确释放
- **异步支持**: 完全支持 CompletableFuture 异步操作
- **错误隔离**: 单个操作的错误不会影响锁机制

#### 5. ModbusInfo 及其子类
- **ModbusInfo**: 抽象基类，定义公共属性
- **ModbusTcpInfo**: TCP 连接信息（IP、端口、slaveId）
- **ModbusSerialInfo**: 串行连接信息（串口、波特率、数据位等）

#### 6. Modbus Slave 服务组件
允许外部 Modbus Master 读写本系统数据：

- **ModbusSlaveRegistry**: Slave 服务注册管理中心
- **ModbusSlaveServer**: 支持 TCP 和 Serial RTU 的 Slave 服务
- **ModbusDataCallback**: 数据回调接口，用户实现此接口处理读写请求
- **CallbackProcessImage**: 将 Modbus4J 请求转发给回调接口
- **ModbusTcpSlaveConfig / ModbusSerialSlaveConfig**: Slave 配置类

## 使用场景

### 场景1：工业自动化系统

**描述**: 在工厂自动化中，多个传感器和执行器通过 Modbus 网络连接到控制系统。

**优势**:
- 连接复用减少网络负载
- 并发控制确保数据一致性
- 自动重连提高系统可靠性

```java
// 创建 Modbus 集成管理器
ModbusIntegration integration = new ModbusIntegration();
integration.onInit();

// 注册多个设备到同一 TCP 网关
ModbusTcpInfo gatewayInfo = new ModbusTcpInfo("192.168.1.100", 502, 1);

// 温度传感器
ModbusSource tempSensor = integration.register(gatewayInfo, "temp-sensor-001");
ModbusTransactionStrategy.executeWithLambda(tempSensor, source -> {
    return source.readHoldingRegisters(0, 2)
        .thenApply(response -> {
            if (response != null && response.isValid()) {
                short[] values = response.getShortData();
                double temperature = values[0] * 0.1; // 转换为实际温度
                System.out.println("温度: " + temperature + "°C");
                return true;
            }
            return false;
        });
});

// 压力传感器
ModbusSource pressureSensor = integration.register(gatewayInfo, "pressure-sensor-001");
ModbusTransactionStrategy.executeWithLambda(pressureSensor, source -> {
    return source.readHoldingRegisters(10, 2)
        .thenApply(response -> {
            if (response != null && response.isValid()) {
                short[] values = response.getShortData();
                double pressure = values[0] * 0.01; // 转换为实际压力
                System.out.println("压力: " + pressure + " MPa");
                return true;
            }
            return false;
        });
});
```

### 场景2：楼宇自控系统

**描述**: 在智能楼宇中，通过串行总线连接各种设备（照明、空调、门禁等）。

**优势**:
- 串行连接的高效管理
- 设备隔离确保安全性
- 配置灵活适应不同设备

```java
// 创建串行连接信息
ModbusSerialInfo serialInfo = new ModbusSerialInfo(
    "COM3", 
    9600, 
    ModbusSerialInfo.DATA_BITS_8, 
    ModbusSerialInfo.STOP_BITS_1, 
    ModbusSerialInfo.NO_PARITY, 
    1000, // 超时1秒
    1      // slaveId
);

// 注册照明控制设备
ModbusSource lightingControl = integration.register(serialInfo, "lighting-control-001");
// 控制照明开关
ModbusTransactionStrategy.executeWithLambda(lightingControl, source -> {
    return source.writeCoil(0, true)  // 开启照明
        .thenApply(response -> {
            if (response != null && response.isValid()) {
                System.out.println("照明控制成功");
                return true;
            }
            return false;
        });
});

// 注册空调控制设备
ModbusSource hvacControl = integration.register(serialInfo, "hvac-control-001");
// 设置温度设定值
ModbusTransactionStrategy.executeWithLambda(hvacControl, source -> {
    return source.writeRegister(0, (short)220)  // 设置22.0°C
        .thenApply(response -> {
            if (response != null && response.isValid()) {
                System.out.println("温度设定成功");
                return true;
            }
            return false;
        });
});
```

### 场景3：能源管理系统

**描述**: 在能源监控中，同时采集多个电表、水表的数据。

**优势**:
- 并发采集提高效率
- 连接复用降低资源消耗
- 异步处理避免阻塞

```java
// 配置高并发支持
Map<String, Object> config = new HashMap<>();
config.put("max_waiters", 10);      // 最大等待数
config.put("wait_timeout", 5000);   // 等待超时5秒

// 模拟配置加载
IntegrationManager manager = mock(IntegrationManager.class);
when(manager.loadConfig(anyString())).thenReturn(config);

ModbusIntegration integration = new ModbusIntegration();
TestTools.setPrivateField(integration, "integrationManager", manager);
integration.onInit();

// 批量注册电表设备
List<ModbusSource> meters = new ArrayList<>();
for (int i = 1; i <= 20; i++) {
    ModbusTcpInfo meterInfo = new ModbusTcpInfo("192.168.1.100", 502, i);
    ModbusSource meter = integration.register(meterInfo, "electric-meter-" + i);
    meters.add(meter);
}

// 并发采集数据
List<CompletableFuture<Boolean>> futures = new ArrayList<>();
for (ModbusSource meter : meters) {
    CompletableFuture<Boolean> future = ModbusTransactionStrategy.executeWithLambda(meter, source -> {
        return source.readHoldingRegisters(0, 4)
            .thenApply(response -> {
                if (response != null && response.isValid()) {
                    short[] values = response.getShortData();
                    double energy = (values[0] << 16 | values[1]) * 0.01; // 32位能量值
                    System.out.println("电表数据: " + energy + " kWh");
                    return true;
                }
                return false;
            });
    });
    futures.add(future);
}

// 等待所有采集完成
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
    .thenRun(() -> System.out.println("所有电表数据采集完成"));
```

## 优点

### 1. 高效的资源管理
- **连接复用**: 多个逻辑设备共享同一物理连接，显著减少网络和串口资源消耗
- **资源池化**: TCP 和串行连接分别管理，提高资源利用率
- **自动清理**: 生命周期管理确保资源正确释放

### 2. 强大的并发控制
- **锁机制**: 内置 ReentrantLock 确保线程安全
- **等待队列**: 支持可配置的等待队列，避免请求丢失
- **超时控制**: 灵活的超时设置，防止无限等待

### 3. 灵活的配置管理
- **动态配置**: 支持运行时配置修改
- **参数验证**: 自动验证配置参数的有效性
- **默认值**: 提供合理的默认配置，降低使用门槛

### 4. 异步编程支持
- **CompletableFuture**: 基于 Java 8 的异步编程模型
- **非阻塞**: 所有操作都是非阻塞的，提高系统响应性
- **链式调用**: 支持异步操作的链式调用和组合

### 5. 设备隔离和安全性
- **设备特定包装**: DeviceSpecificModbusSource 确保设备级别的隔离
- **SlaveId 管理**: 自动处理多设备共享连接时的 slaveId 冲突
- **错误隔离**: 单个设备的错误不会影响其他设备

## 使用注意事项

### 1. 配置参数设置

#### 最大等待数 (max_waiters)
- **范围**: 1-10
- **默认值**: 3
- **建议**: 根据系统并发需求调整，过高可能导致内存消耗增加

```java
// 推荐配置
Map<String, Object> config = new HashMap<>();
config.put("max_waiters", 5);  // 中等并发场景
```

#### 等待超时 (wait_timeout)
- **范围**: 1000-10000 毫秒
- **默认值**: 2000 毫秒
- **建议**: 根据网络延迟和设备响应时间调整

```java
// 推荐配置
Map<String, Object> config = new HashMap<>();
config.put("wait_timeout", 3000);  // 3秒超时，适合大多数工业场景
```

### 2. 资源管理最佳实践

#### 连接注册和释放
```java
// 正确的注册方式
ModbusSource source = integration.register(modbusInfo, "device-001");

// 使用完成后，如果不再需要可以移除
source.removeIntegration("device-001");
```

#### 生命周期管理
```java
// 应用启动时初始化
ModbusIntegration integration = new ModbusIntegration();
integration.onInit();
integration.onStart();

// 应用关闭时释放资源
integration.onPause();
integration.onRelease();
```

### 3. 并发访问控制

#### ⚠️ 重要：必须使用策略锁机制

**为什么必须使用锁机制？**

在多设备共享同一 Modbus 连接的环境中，直接调用 `source.readHoldingRegisters()` 等方法会导致严重的并发问题：

1. **异步数据混乱**: 多个线程同时访问同一连接时，数据包可能交错发送和接收
2. **响应错乱**: 设备A的响应可能被线程B误认为是自己的响应
3. **SlaveId 冲突**: 多个设备使用不同 slaveId 时，可能出现响应混淆
4. **连接状态不一致**: 并发操作可能导致连接状态管理混乱

**正确的做法：所有 Modbus 操作都必须通过 `ModbusTransactionStrategy.executeWithLambda` 执行**

#### 策略锁使用模式
```java
// ✅ 正确：使用策略锁包装所有操作
ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
    return source.readHoldingRegisters(0, 10)
        .thenApply(response -> {
            if (response != null && response.isValid()) {
                short[] values = response.getShortData();
                // 处理数据...
                return true;
            }
            return false;
        });
});

// ❌ 错误：直接调用会导致并发问题
source.readHoldingRegisters(0, 10)
    .thenAccept(response -> {
        // 这种方式在多设备环境下不安全！
    });
```

#### 直接访问的适用场景

**只有在以下特殊情况下才考虑直接访问：**

1. **独占访问**: 确保只有一个线程会访问该 ModbusSource 底层端口或连接
   ```java
   // 在单线程或确保独占的情况下
   synchronized (modbusSource) {
       source.readHoldingRegisters(0, 10)
           .thenAccept(response -> {
               // 处理响应
           });
   }
   ```

2. **超高频读取**: 当读取频率极高，且能确保没有其他并发访问时
   ```java
   // 适用于专用的读取线程
   ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
   scheduler.scheduleAtFixedRate(() -> {
       source.readHoldingRegisters(0, 10)
           .thenAccept(response -> {
               // 高频读取处理
           });
   }, 0, 100, TimeUnit.MILLISECONDS); // 每100ms读取一次
   ```

**⚠️ 注意**: 即使在上述场景中，仍建议使用策略锁机制以确保安全性。


### 4. 错误处理和异常情况

#### 连接失败处理
```java
source.readHoldingRegisters(0, 10)
    .thenAccept(response -> {
        if (response == null) {
            System.err.println("读取失败：连接异常");
        } else if (!response.isValid()) {
            System.err.println("读取失败：响应无效");
        } else {
            // 正常处理响应
            short[] values = response.getShortData();
            // ...
        }
    });
```

### 5. 性能优化建议

#### 连接复用
```java
// 推荐：多个设备使用相同连接信息
ModbusTcpInfo sharedInfo = new ModbusTcpInfo("192.168.1.100", 502, 1);

ModbusSource device1 = integration.register(sharedInfo, "device-001");
ModbusSource device2 = integration.register(sharedInfo, "device-002");
// device1 和 device2 共享同一个 TCP 连接
```

#### 批量操作
```java
// 推荐：批量读取减少网络往返
CompletableFuture<ReadHoldingRegistersResponse> future = 
    source.readHoldingRegisters(0, 20); // 一次性读取20个寄存器
```

#### 异步处理
```java
// 推荐：使用异步处理提高并发性能
List<CompletableFuture<?>> futures = new ArrayList<>();

futures.add(source.readHoldingRegisters(0, 10));
futures.add(source.readInputRegisters(20, 5));
futures.add(source.readCoils(100, 8));

// 等待所有操作完成
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
    .thenRun(() -> System.out.println("所有操作完成"));
```

### 6. 安全注意事项

#### 设备隔离
```java
// 每个设备使用唯一的 identity
ModbusSource device1 = integration.register(modbusInfo, "unique-device-001");
ModbusSource device2 = integration.register(modbusInfo, "unique-device-002");
```

#### 资源清理
```java
// 应用退出时确保资源释放
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    integration.onRelease();
}));
```

## 依赖

### 核心依赖
- **Modbus4J**: 开源的 Modbus 通信库
  ```xml
  <dependency>
      <groupId>com.github.tusky2015</groupId>
      <artifactId>modbus4j</artifactId>
      <version>v3.1.9</version>
  </dependency>
  ```

### 系统要求
- **Java**: 8 或更高版本
- **内存**: 建议 512MB 以上可用内存
- **网络**: 支持 TCP/IP 或串行通信

## 故障排除

### 常见问题

#### 1. 连接失败
**现象**: 无法建立 Modbus 连接
**解决**: 
- 检查网络连接和设备地址
- 验证端口和协议设置
- 确认设备电源和通信状态

#### 2. 超时错误
**现象**: 操作频繁超时
**解决**:
- 增加 wait_timeout 配置值
- 检查网络延迟和设备响应时间
- 减少 max_waiters 避免过载

#### 3. 并发冲突
**现象**: 多设备访问时出现数据不一致
**解决**:
- 确保正确使用锁机制
- 检查设备 slaveId 配置
- 使用 DeviceSpecificModbusSource 进行设备隔离

### 日志分析

启用详细日志以便问题诊断：
```java
// 设置日志级别
Logger.getLogger(ModbusSource.class.getName()).setLevel(Level.FINE);
Logger.getLogger(ModbusIntegration.class.getName()).setLevel(Level.FINE);
```

## 版本历史

### v1.1.0
- 新增 Modbus Slave 服务功能
- 支持 TCP Slave 和 Serial RTU Slave
- 实现全部 8 个标准功能码（01-04 读，05-06 单写，15-16 批量写）
- 提供回调模式接口 `ModbusDataCallback`

### v1.0.0
- 初始版本发布
- 支持 Modbus TCP 和串行协议
- 实现连接复用和并发控制
- 提供完整的异步操作接口

## 许可证

本项目采用 Apache License 2.0，详见 LICENSE 文件。

## 协议声明
1. 核心依赖：本插件基于 **ECAT Core**（Apache License 2.0）开发，Core 项目地址：https://github.com/ecat-project/ecat-core。
2. 插件自身：本插件的源代码采用 [Apache License 2.0] 授权。
3. 合规说明：使用本插件需遵守 ECAT Core 的 Apache 2.0 协议规则，若复用 ECAT Core 代码片段，需保留原版权声明。

### 许可证获取
- ECAT Core 完整许可证：https://github.com/ecat-project/ecat-core/blob/main/LICENSE
- 本插件许可证：./LICENSE

