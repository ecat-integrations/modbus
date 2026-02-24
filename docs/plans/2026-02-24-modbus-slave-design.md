# Modbus Slave 回调服务设计文档

## 概述

为 ecat-integrations/modbus 增加 Slave 服务能力，允许外部 Modbus Master 读取本系统提供的数据。

## 核心设计

### 回调模式

```
外部 Master → Modbus4J Slave → CallbackProcessImage → ModbusDataCallback → 使用集成实现
```

### 数据流

1. 外部 Master 发起 Modbus 请求
2. Modbus4J Slave 接收请求
3. CallbackProcessImage 拦截请求
4. 根据请求类型调用 ModbusDataCallback 对应方法
5. 使用集成返回 byte[] 或 boolean 结果
6. CallbackProcessImage 解析并返回给 Modbus4J
7. Modbus4J 封装响应帧发送

### 设计决策

1. **无缓存设计**：每次请求直接传递给 callback 处理，不做缓存
2. **读操作单数形式**：Modbus4J 内部对批量读取循环调用单寄存器方法
3. **写操作区分**：区分单个写入（功能码 05/06）和批量写入（功能码 15/16）

## 核心接口

### ModbusDataCallback 接口

```java
public interface ModbusDataCallback {
    boolean SUCCESS = true;
    boolean FAILURE = false;

    // 读操作 - 返回 byte[]，null 表示失败
    byte[] onReadCoil(int slaveId, int address);              // 功能码 01，返回 1 字节
    byte[] onReadDiscreteInput(int slaveId, int address);     // 功能码 02，返回 1 字节
    byte[] onReadHoldingRegister(int slaveId, int address);   // 功能码 03，返回 2 字节 Big-Endian
    byte[] onReadInputRegister(int slaveId, int address);     // 功能码 04，返回 2 字节 Big-Endian

    // 写操作 - 返回 boolean
    boolean onWriteSingleCoil(int slaveId, int address, boolean value);                    // 功能码 05
    boolean onWriteSingleRegister(int slaveId, int address, byte[] value);                 // 功能码 06
    boolean onWriteMultipleCoils(int slaveId, int startAddress, byte[] packedBits, int quantity);  // 功能码 15
    boolean onWriteMultipleRegisters(int slaveId, int startAddress, byte[] values);        // 功能码 16
}
```

### AbstractModbusDataCallback 默认实现

```java
public abstract class AbstractModbusDataCallback implements ModbusDataCallback {
    // 所有方法默认返回 FAILURE/null
    // 子类只需重写需要的方法
}
```

### CallbackProcessImage 实现

实现 Modbus4J 的 `ProcessImage` 接口，将调用转发给 `ModbusDataCallback`：

| ProcessImage 方法 | Callback 方法 | 说明 |
|------------------|---------------|------|
| `getCoil()` | `onReadCoil()` | 读取单个线圈 |
| `getInput()` | `onReadDiscreteInput()` | 读取单个离散输入 |
| `getHoldingRegister()` | `onReadHoldingRegister()` | 读取单个保持寄存器 |
| `getInputRegister()` | `onReadInputRegister()` | 读取单个输入寄存器 |
| `writeCoil()` | `onWriteSingleCoil()` | 写入单个线圈 |
| `writeCoils()` | `onWriteMultipleCoils()` | 写入多个线圈 |
| `writeHoldingRegister()` | `onWriteSingleRegister()` | 写入单个寄存器 |
| `writeHoldingRegisters()` | `onWriteMultipleRegisters()` | 写入多个寄存器 |

## 配置设计

### 唯一标识

- TCP: `ipAddress:port`
- Serial: `portName`

### 两级索引

```
ModbusSlaveRegistry
├── connectionId ("192.168.1.1:502" 或 "/dev/ttyUSB0")
│   └── ModbusSlaveServer 实例
│       └── processImageMap
│           ├── slaveId=1 → CallbackProcessImage → ModbusDataCallback
│           └── slaveId=2 → CallbackProcessImage → ModbusDataCallback
```

### 配置类

```java
// 基类
public abstract class ModbusSlaveConfig {
    protected final int slaveId;
    protected final ModbusProtocol protocol;
    protected ModbusDataCallback callback;
    
    public abstract String getConnectionIdentity();
}

// TCP 配置
public class ModbusTcpSlaveConfig extends ModbusSlaveConfig {
    private final String ipAddress;
    private final int port;
    // 支持 TCP 和 RTU_OVER_TCP 协议
}

// Serial 配置
public class ModbusSerialSlaveConfig extends ModbusSlaveConfig {
    private final String portName;
    private final int baudRate;
    private final int dataBits;
    private final int stopBits;
    private final int parity;
}
```

## 使用示例

### TCP Slave 示例

```java
ModbusSlaveRegistry registry = new ModbusSlaveRegistry();

ModbusTcpSlaveConfig config = new ModbusTcpSlaveConfig(1, "0.0.0.0", 5020);
config.setCallback(new AbstractModbusDataCallback() {
    @Override
    public byte[] onReadHoldingRegister(int slaveId, int address) {
        // 业务逻辑：返回寄存器值
        int value = getDeviceValue(address);
        return new byte[] {
            (byte) ((value >> 8) & 0xFF),
            (byte) (value & 0xFF)
        };
    }
    
    @Override
    public boolean onWriteSingleRegister(int slaveId, int address, byte[] value) {
        int val = ((value[0] & 0xFF) << 8) | (value[1] & 0xFF);
        setDeviceValue(address, val);
        return SUCCESS;
    }
});

registry.register(config);
registry.start(config.getConnectionIdentity(), config.getSlaveId());
```

### Serial RTU Slave 示例

```java
ModbusSlaveRegistry registry = new ModbusSlaveRegistry();

ModbusSerialSlaveConfig config = new ModbusSerialSlaveConfig(
    36,                           // slaveId
    "/dev/ttyUSB0",               // portName
    9600,                         // baudRate
    8,                            // dataBits
    ModbusSerialSlaveConfig.ONE_STOP_BIT,
    ModbusSerialSlaveConfig.NO_PARITY
);
config.setCallback(new SmartStationDeviceCallback());

registry.register(config);
registry.start(config.getConnectionIdentity(), config.getSlaveId());
```

## 文件结构

```
ecat-integrations/modbus/src/main/java/com/ecat/integration/ModbusIntegration/
└── Slave/
    ├── ModbusDataCallback.java          # 回调接口
    ├── AbstractModbusDataCallback.java  # 默认实现
    ├── CallbackProcessImage.java        # ProcessImage 实现
    ├── ModbusSlaveServer.java           # Slave 服务主类（支持 TCP/Serial）
    ├── ModbusSlaveRegistry.java         # 注册管理
    ├── ModbusSlaveConfig.java           # 配置基类
    ├── ModbusTcpSlaveConfig.java        # TCP 配置
    └── ModbusSerialSlaveConfig.java     # Serial 配置

ecat-integrations/modbus/src/test/java/com/ecat/integration/ModbusIntegration/Slave/
├── SmartStationDeviceCallback.java      # 智慧站房设备模拟器
├── SerialRtuSlaveServerDemo.java        # Serial Slave Demo
├── SerialRtuMasterClientDemo.java       # Serial Master Demo
├── ModbusSlaveRegistryTest.java         # 单元测试
└── ModbusTcpSlaveConfigTest.java        # 单元测试
```

## 支持的功能码

| 功能码 | 名称 | 类型 | 实现 |
|--------|------|------|------|
| 01 | Read Coils | 读 | ✅ |
| 02 | Read Discrete Inputs | 读 | ✅ |
| 03 | Read Holding Registers | 读 | ✅ |
| 04 | Read Input Registers | 读 | ✅ |
| 05 | Write Single Coil | 写 | ✅ |
| 06 | Write Single Register | 写 | ✅ |
| 15 (0x0F) | Write Multiple Coils | 写 | ✅ |
| 16 (0x10) | Write Multiple Registers | 写 | ✅ |

## 技术要点

### 1. Modbus4J 批量读取机制

Modbus4J 的 `ProcessImage` 接口只支持单寄存器读取方法。当 Master 请求 `read(start=10, quantity=5)` 时，Modbus4J 内部会循环调用 5 次 `getHoldingRegister(offset)`，每次 offset 从 10 到 14。

**设计决策**：接口方法命名为单数形式（`onReadHoldingRegister`），反映每次只读一个寄存器的事实。

### 2. 写操作区分

Modbus4J 对写操作有明确区分：
- `writeHoldingRegister()` → 功能码 06（单个写入）
- `writeHoldingRegisters()` → 功能码 16（批量写入）

**设计决策**：接口区分 `onWriteSingleRegister` 和 `onWriteMultipleRegisters`，让用户可以针对不同场景优化处理。

### 3. 异步启动

Modbus4J 的 `TcpSlave.start()` 和 Serial Slave 的 start() 会阻塞等待连接。因此 `ModbusSlaveServer.start()` 在后台线程中启动服务。

### 4. 返回值约定

| 操作类型 | 返回值 | 说明 |
|---------|--------|------|
| 读操作 | `byte[]` | 数据字节，`null` 表示失败 |
| 写操作 | `boolean` | `SUCCESS`/`FAILURE` |

## 测试验证

### Demo 测试覆盖

SerialRtuMasterClientDemo 包含 20 个自动化测试：

| Test | 功能 | 功能码 |
|------|------|--------|
| 1-2 | Holding Register 读取（单个+批量） | 03 |
| 3-4 | Holding Register 单个写入+验证 | 06 |
| 5-6 | Holding Register 批量写入+验证 | 16 |
| 7-8 | 非连续地址写入+验证 | 06 |
| 9-10 | Coil 读取（单个+批量） | 01 |
| 11-12 | Coil 单个写入+验证 | 05 |
| 13-14 | Coil 批量写入+验证 | 15 |
| 15-16 | Discrete Input 读取（单个+批量） | 02 |
| 17-18 | Input Register 读取（单个+批量） | 04 |
| 19 | Input Register 与 Holding Register 值一致性 | 03/04 |
| 20 | Coil OFF 写入+验证 | 05 |

### Demo 交互命令

```
read <addr> [qty]          - 读取 Holding Register (功能码 03)
write <addr> <val> ...     - 写入 Holding Register (功能码 06/16)
coil <addr> [qty]          - 读取 Coil (功能码 01)
coilw <addr> <0|1> ...     - 写入 Coil (功能码 05/15)
di <addr> [qty]            - 读取 Discrete Input (功能码 02)
ir <addr> [qty]            - 读取 Input Register (功能码 04)
test                       - 运行自动化测试
quit                       - 退出
```

## 实现状态

- [x] Phase 1: TCP Slave + 四种数据类型
- [x] Phase 2: Serial RTU Slave
- [x] 完整测试覆盖（163 个单元测试通过）
- [x] Demo 验证（20 个自动化测试）
