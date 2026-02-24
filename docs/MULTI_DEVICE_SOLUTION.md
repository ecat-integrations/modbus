# Modbus多设备共享连接解决方案

## 问题描述

在原有的ModbusIntegration实现中，当多个设备共享同一个串口（RS485）时，存在以下问题：

1. **slaveId冲突**：多个设备使用同一个ModbusSource实例，导致所有设备都使用第一个注册设备的slaveId
2. **数据错误**：第二个及后续设备读取时会收到错误的数据，因为Modbus请求发送到了错误的slaveId
3. **TCP连接同样存在问题**：虽然TCP连接可以支持多个slaveId，但原有的实现没有正确处理这种情况

## 解决方案设计

### 设计模式：装饰器模式 + 代理模式

我们采用了**装饰器模式**和**代理模式**的组合来解决这个问题的核心思想：

1. **共享底层连接**：多个设备共享同一个ModbusMaster和锁机制
2. **设备特定包装器**：每个设备获得一个DeviceSpecificModbusSource包装器
3. **透明调用**：调用者无需修改任何代码，API保持完全兼容

### 核心组件

#### 1. DeviceSpecificModbusSource（装饰器）
- 继承ModbusSource，保持API兼容性
- 持有对共享ModbusSource的引用（delegate）
- 所有Modbus操作都委托给delegate，但使用设备特定的slaveId
- 锁管理完全委托给共享的delegate

#### 2. ModbusSource增强（被装饰对象）
- 构造函数改为protected，只能通过ModbusIntegration创建
- 新增带slaveId参数的内部方法（protected）
- 原有公共方法调用新的带slaveId方法，使用默认slaveId

#### 3. ModbusIntegration统一处理（工厂）
- 统一处理TCP和串口连接的创建逻辑
- 为每个设备创建DeviceSpecificModbusSource包装器
- 确保相同连接的设备共享同一个底层ModbusSource

## 实现细节

### 文件修改清单

1. **ModbusSource.java**
   - 构造函数改为protected
   - 添加带slaveId参数的内部方法
   - 修改原有方法调用新的内部方法

2. **DeviceSpecificModbusSource.java**（新增）
   - 继承ModbusSource
   - 委托所有操作给共享的delegate
   - 使用设备特定的slaveId

3. **ModbusIntegration.java**
   - 统一createOrGetSource方法
   - 返回DeviceSpecificModbusSource实例
   - 保持连接复用逻辑

### 关键代码示例

#### DeviceSpecificModbusSource核心逻辑
```java
@Override
public CompletableFuture<ReadHoldingRegistersResponse> readHoldingRegisters(int startAddress, int numberOfRegisters) {
    return delegate.readHoldingRegistersWithSlaveId(deviceSlaveId, startAddress, numberOfRegisters);
}
```

#### ModbusIntegration统一处理
```java
private ModbusSource createOrGetSource(ModbusInfo info, String identity) {
    String connectionIdentity = getConnectionIdentity(info);
    Map<String, ModbusSource> sourcePool = getSourcePool(info);
    
    ModbusSource sharedSource = sourcePool.computeIfAbsent(connectionIdentity, 
        k -> new ModbusSource(info, maxWaiters, waitTimeoutMs));
    
    DeviceSpecificModbusSource deviceSource = new DeviceSpecificModbusSource(sharedSource, info.getSlaveId());
    return deviceSource;
}
```

## 解决方案优势

### 1. 完全向后兼容
- 调用者无需修改任何代码
- API接口保持不变
- 现有功能完全保留

### 2. 正确的slaveId隔离
- 每个设备使用正确的slaveId
- 避免数据发送到错误设备
- 支持串口和TCP两种协议

### 3. 高效的资源利用
- 共享ModbusMaster连接
- 共享锁机制，避免竞态条件
- 减少资源占用

### 4. 良好的可扩展性
- 易于添加新的Modbus操作
- 支持更多协议类型
- 便于维护和调试

## 使用示例

### 串口多设备场景
```java
ModbusIntegration integration = new ModbusIntegration();
integration.onInit();

// 设备1：COM1端口，slaveId=1
ModbusSerialInfo device1Info = new ModbusSerialInfo();
device1Info.setPortName("COM1");
device1Info.setSlaveId(1);
ModbusSource device1 = integration.register(device1Info, "device1");

// 设备2：COM1端口，slaveId=2（共享串口）
ModbusSerialInfo device2Info = new ModbusSerialInfo();
device2Info.setPortName("COM1");
device2Info.setSlaveId(2);
ModbusSource device2 = integration.register(device2Info, "device2");

// 每个设备会自动使用正确的slaveId
CompletableFuture<ReadHoldingRegistersResponse> future1 = device1.readHoldingRegisters(0, 10);
CompletableFuture<ReadHoldingRegistersResponse> future2 = device2.readHoldingRegisters(0, 10);
```

### TCP多设备场景
```java
// 设备1：TCP连接，slaveId=1
ModbusTcpInfo tcpDevice1Info = new ModbusTcpInfo();
tcpDevice1Info.setIpAddress("192.168.1.100");
tcpDevice1Info.setPort(502);
tcpDevice1Info.setSlaveId(1);
ModbusSource tcpDevice1 = integration.register(tcpDevice1Info, "tcp_device1");

// 设备2：相同TCP连接，slaveId=2
ModbusTcpInfo tcpDevice2Info = new ModbusTcpInfo();
tcpDevice2Info.setIpAddress("192.168.1.100");
tcpDevice2Info.setPort(502);
tcpDevice2Info.setSlaveId(2);
ModbusSource tcpDevice2 = integration.register(tcpDevice2Info, "tcp_device2");
```

## 测试覆盖

我们创建了全面的测试套件：

1. **DeviceSpecificModbusSourceTest**
   - 测试装饰器模式的所有功能
   - 验证slaveId正确传递
   - 测试锁管理委托

2. **ModbusIntegrationMultiDeviceTest**
   - 测试多设备共享连接
   - 验证不同协议类型的处理
   - 测试连接复用逻辑

3. **ModbusSourceSlaveIdTest**
   - 测试新增的带slaveId方法
   - 验证原有方法兼容性
   - 测试异常处理

## 性能影响

### 内存开销
- 每个设备增加一个DeviceSpecificModbusSource实例（很小）
- 共享同一个ModbusMaster和ExecutorService

### 运行时开销
- 增加一层方法调用（可忽略）
- 无额外的网络或锁开销

### 并发性能
- 保持原有的锁机制
- 多设备访问同一连接时仍然串行化（符合RS485协议要求）

## 总结

这个解决方案通过装饰器模式优雅地解决了多设备共享Modbus连接时的slaveId冲突问题，同时保持了完全的向后兼容性。方案具有以下特点：

1. **正确性**：确保每个设备使用正确的slaveId
2. **兼容性**：调用者无需修改任何代码
3. **高效性**：共享连接和锁机制，资源利用率高
4. **可维护性**：清晰的代码结构，完善的测试覆盖
5. **可扩展性**：易于支持新的协议和功能

该方案不仅解决了当前的串口多设备问题，也为TCP多设备场景提供了统一的解决方案，为系统的长期发展奠定了良好的基础。
