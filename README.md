# integration-modbus

> **坐标**: `com.ecat:integration-modbus`
> **版本**: 3.1.0（见 pom.xml）
> **依赖**: `ecat-core ^3.1.0`、`integration-ecat-common ^3.0.0`、`integration-serial ^3.0.0`（RTU 路径运行时必需；见 `src/main/resources/ecat-config.yml`）

## 概述

ECAT Modbus 集成模块为所有 ecat-integrations 提供完整的 Modbus TCP/RTU 访问能力，基于 Modbus4J 库构建，提供连接管理（复用/重连/池化）与并发控制（源锁/等待队列/超时）。对外 SDK 能力面共三种模式：

| 模式 | 入口 | 适用 |
|------|------|------|
| 主动轮询 | `Sdk/ModbusPolling` | L3 设备仓周期采集的标准入口（调度/锁/韧性内置） |
| 命令/写事务 | `ModbusTransactionStrategy.executeWithLambda` | 写命令、需要有限等待语义的一次性事务 |
| Slave 被动应答 | `registerSlave`/`startSlave` + `ModbusDataCallback` | 本系统作为 Modbus 从站，供外部 Master 读写 |

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

### 🖥️ Slave 服务
- **回调模式**: 通过回调接口处理外部 Master 的读写请求
- **协议完整**: 支持全部 8 个标准功能码（01-04 读，05-06 单写，15-16 批量写）
- **双模式**: 同时支持 TCP Slave 和 Serial RTU Slave

## 架构设计

### 核心组件

#### 1. ModbusIntegration
主要的集成管理类，负责：
- Modbus 资源的注册和管理（`register` / `registerSlave`）
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
- **ModbusInfo**: 抽象基类，定义公共属性（slaveId、protocol）
- **ModbusTcpInfo**: TCP 连接信息（IP、端口、slaveId、protocol、timeout）
- **ModbusSerialInfo**: 串行连接信息（串口、波特率、数据位、timeout 等）

#### 6. Modbus Slave 服务组件
允许外部 Modbus Master 读写本系统数据：

- **ModbusSlaveRegistry**: Slave 服务注册管理中心
- **ModbusSlaveServer**: 支持 TCP 和 Serial RTU 的 Slave 服务
- **ModbusDataCallback**: 数据回调接口，用户实现此接口处理读写请求
- **CallbackProcessImage**: 将 Modbus4J 请求转发给回调接口
- **ModbusTcpSlaveConfig / ModbusSerialSlaveConfig**: Slave 配置类

#### 7. 超时机制（要点）

- **事务超时**：等待从站响应帧的时间——TCP 经 ConfigEntry `comm_settings.timeout` 配置，RTU 经 `serial_settings.timeout` 配置
- **锁等待超时**：共享连接上排队等锁的时间——集成配置 `wait_timeout`（全局）
- 一次操作的完整耗时 = 锁等待时间 + 事务执行时间
- 传递链路、协作时序图与场景参考值表见 **[docs/timeout-architecture.md](docs/timeout-architecture.md)**

## SDK 快速上手

### 前置条件

1. **Maven 依赖**：消费方在自己的 pom 声明本模块（scope=provided，运行时由宿主 core 装载），片段见下文「依赖」节。
2. **获取集成实例与 ModbusSource**：集成实例由 core 装载，设备仓从 IntegrationRegistry 取用；`register` 返回的源按 `ip:port`（TCP）或串口名（RTU）共享复用：

```java
ModbusIntegration modbus = (ModbusIntegration) core.getIntegrationRegistry()
        .getIntegration("integration-modbus");

ModbusSource source = modbus.register(
        new ModbusTcpInfo("192.168.1.100", 502, 1),   // TCP；RTU 用 ModbusSerialInfo
        "my-device-001");                              // 设备唯一标识
```

3. **端口分配**：测试与联调环境的设备端口/串口统一走 workspace 端口分配真相源 `.claude/skills/ecat-integration-test-env-prepare/config/port-allocation.json`，勿自选端口（同口冲突会静默丢帧）。

### 主动轮询（ModbusPolling，L3 设备仓标准入口）

设备仓的 modbus 周期采集**只走本 SDK**（`Sdk/ModbusPolling`，L2 传输 SDK 层轮询模式的 modbus 形态）——调度（域自持定时池 `Sdk/ModbusSdkTimers`，周期链骨架归 core 库 PeriodicChain——29 号 v2 S1 已脱离 core 调度引擎/resolver）/源锁/锁忙跳过/事务内建硬超时/熔断前置/异常韧性（永不注销）/统一日志全部内置，设备仓的执行词汇只剩 round 函数 + 属性灌入。**canary 实证用法**（批 1 已迁移：environnement-sa、dypsensor）：

```java
// 设备 start()（environnement-sa EsaDeviceBase / dypsensor DYPA02Device 同款）
ModbusPolling.on(this, modbusSource)           // this = 设备宿主（RemovalHost），生产唯一形态
        .round(this::readRegisters)              // 一轮读什么（签名与旧 executePolling lambda 一致，迁移=搬函数体）
        .every(5, TimeUnit.SECONDS)              // 默认 fixedDelay：完成点+period=下轮；FixedRate 仓加 .fixedRate()
        .start();                                // 周期链在域自持定时池上自排；句柄内绑宿主生命周期

// readXxx 变 round 契约（usr 模板族 8 仓同款改法）
CompletableFuture<Boolean> readRegisters(ModbusSource source) {
    return source.readHoldingRegisters(BLOCK.startAddress, BLOCK.registerCount)
            .thenApply(resp -> { /* 解析 + attr.updateValue + publicAttrsState */ return true; });
}
```

- **round 契约**：`Function<ModbusSource, CompletableFuture<Boolean>>`（严格 Boolean）——true=业务成功，false=业务失败（统一 warn，`BUSINESS_FALSE`），异常=传输错误（统一 error，轮询不注销）；锁忙 SDK 内部消化（`LOCK_BUSY_SKIPPED`，不算失败）。
- **可选步骤**：`initialDelay(n, unit)`（santak 5s 型首延迟）/ `fixedRate()`（aogan/ebyte/epever/juyingele/zhiqwl 型）/ `named(label)`（zhaorong 校时链定位标签）/ `onRound(Consumer<RoundReport>)`（五分类结局观测——24 个 modbus 族仓的 PollingLockBusySkipTest 回归锁在用，R8 复核保留；连续失败→恢复有断连状态转移行：首败 WARN/恢复 INFO 去重）；round 体内多段块读留隙用 `polling.delay(ms)`（返回到点完成的 CF，saimosen/tianhong 在用）；超时由事务内建硬超时兜底（SDK 级 `timeoutMs` 收紧词汇零消费已删，R8 剃刀）。
- **调度自持**（29 号 v2 S1）：周期链跑在本仓 `Sdk/ModbusSdkTimers` 域池（daemon 线程 `ecat-modbus-sched-N`，尺寸 2——轮询发起是 µs 级提交，阻塞 IO 全在 ModbusIoPool 16 条；集成 `onRelease` 兜底停机），网格策略（fixedDelay/fixedRate/过期即弃）在 `Sdk/ModbusPollingSchedule`；**不再解析 core 调度引擎，测试零引擎装配**——设备仓单测直接 `start()` 即跑（真池），网格数学确定性断言见 Sdk 包 `ModbusPollingChainTest`（fake 定时缝）。
- **生命周期**：`on(this, source)` 工厂 host 必填——`start()` 内部经 `RemovalHost.onRemove` 把 cancel 注册进宿主设备的移除动作，设备移除 sweep（`cancelManagedTasks`）时 LIFO 自动执行——设备仓 `stop()/release()` **无需保存句柄、无需 cancel 样板**（cancel 幂等，`cancel(false)` 不中断在飞事务）。测试/独立场景传假宿主（`action -> {}` 或收集断言型）。
- **何时用哪个模式**：周期读寄存器 → 本 SDK；写命令/有限等待 → 既有 `executeWithLambda`（闸内等待语义保留）；Slave 从机 → `ModbusSlaveServer` 族（不动）。
- 契约细节与五维+组合单测见 `ModbusPolling` 类 Javadoc 与 `ModbusPollingSdkTest`；迁移操作手册（含测试三类破坏面改写法）见 workspace `arch-review-20260815/30-transport-sdk-survey/09-migration-handbook-v2.md`。

### Slave 被动应答（registerSlave + ModbusDataCallback）

外部 Modbus Master 读写本系统数据时使用：实现回调 → 注册 → 启动三步。回调只覆写需要的功能码，未覆写的走 `AbstractModbusDataCallback` 基类默认拒绝（读返 0 / 写返 false，Master 侧收到异常响应）。

```java
// ── 1. 定义回调：继承 AbstractModbusDataCallback，只覆写需要的功能码 ──
public class MySlaveCallback extends AbstractModbusDataCallback {
    private final Map<Integer, Short> registerImage;   // 地址 → 寄存器值内存映像
    // 读路径建议走内存映像（读回调高频、不触 IO），映像由轮询/总线事件刷新

    @Override
    public short onReadHoldingRegister(int slaveId, int address) {          // 功能码 03
        return registerImage.getOrDefault(address, (short) 0);
    }

    @Override
    public boolean onWriteSingleRegister(int slaveId, int address, short value) {  // 功能码 06
        // 写路径：把值落到目标设备/属性。回调在 modbus4j 协议线程上同步执行，
        // 内部等待必须有限且不超过 WRITE_CALLBACK_BUDGET_GUIDE_MS（8000ms），
        // 超时返回 false 让 Master 收到失败响应而非挂死
        return applyToDevice(address, value);
    }
}

// ── 2. 注册并启动（TCP Slave）──
ModbusTcpSlaveConfig config = new ModbusTcpSlaveConfig(1, "0.0.0.0", 5020);  // slaveId=1，监听 5020
config.setCallback(new MySlaveCallback());
modbus.registerSlave(config);
modbus.startSlave(config.getConnectionIdentity(), config.getSlaveId());

// ── 3. Serial RTU Slave：换配置类，其余三步同上 ──
// 串口由 registerSlave 内部经 integration-serial 自动注册获取，勿自建 SerialSource
ModbusSerialSlaveConfig serialConfig = new ModbusSerialSlaveConfig(
        36, "/dev/ttyUSB0", 9600, 8,
        ModbusSerialSlaveConfig.ONE_STOP_BIT, ModbusSerialSlaveConfig.NO_PARITY);
serialConfig.setCallback(new MySlaveCallback());
modbus.registerSlave(serialConfig);
modbus.startSlave(serialConfig.getConnectionIdentity(), serialConfig.getSlaveId());

// ── 4. 停止与查态 ──
modbus.stopSlave(config.getConnectionIdentity(), config.getSlaveId());
modbus.isSlaveRunning(config.getConnectionIdentity());
modbus.unregisterSlave(config.getConnectionIdentity(), config.getSlaveId());
```

- 8 个功能码回调全表见 `Slave/ModbusDataCallback.java`（01-04 读、05/06 单写、15/16 批量写）。
- **写回调阻塞预算契约**：写回调在 modbus4j 协议线程上同步等待，实现方必须有限等待（指导值 `AbstractModbusDataCallback.WRITE_CALLBACK_BUDGET_GUIDE_MS` = 8000ms），禁止无超时的 `future.get()`；生产级写闸样板见 leitechina `slave/Callback.java`。
- **生产接线样板**：leitechina `slave/Device.java` 的 `start()`（取集成 → 建回调 → registerSlave → startSlave 全链路）。
- **可跑验证**：`src/test` 下 `SerialRtuSlaveServerDemo` + `SmartStationDeviceCallback` 配 `SerialRtuMasterClientDemo` 可在 socat 虚拟串口对上跑通 RTU Slave/Master 全链路（见「测试」节）。

### 锁表独立与嵌套取锁纪律（与 serial 的关系）

- **serial 侧守卫不覆盖 modbus**：modbus 的源锁表是独立实现（`ModbusSource.acquire/tryAcquire`，同型拷贝而非共享）。serial 2026-08-29 上线的「同线程嵌套取锁 fail-fast 守卫」（vaisala 事故 SDK 层加固，见 serial README）机制上不触及本仓；modbus 侧无事故证据，按「如非必要勿增实体」暂不加（36 号设计 §五裁决，如需另立）。
- **纪律同型**：round/事务临界体内（锁已由 `executePolling`/`executeWithLambda` 持有）追加读/写，直接用 SDK 注入的 `source` 调 `readHoldingRegisters`/`writeRegister` 等方法（round 契约本就如此，方法内部不再取锁），**勿再包一层 `executeWithLambda`/`executePolling` 二次取锁**——同线程嵌套取锁同样是自死锁形态（serial 侧已 fail-fast，本仓当前会静默等锁到超时）。
- 入口选择与 serial 同精神：命令/写事务 → `executeWithLambda`（写闸有限等待语义保留）；周期轮询 → `ModbusPolling`（内部 `executePolling` + `tryAcquire` 锁忙即弃本轮，`LOCK_BUSY_SKIPPED` 不算失败）。
- 执行器选择纪律（纯内存计时走 core `getBizScheduler`、阻塞 IO 走 `HostedExecutors.bounded(1, 宿主)` 单飞道或改全异步链）与车道化样板见 serial README「执行器选择」节——机制属 core 库与消费方，与本仓锁表无关，消费方纪律一致。

## 核心配置

### 集成配置（integration-modbus.yml）

| 配置项 | 范围 | 默认值 | 说明 |
|--------|------|--------|------|
| `max_waiters` | 1-10 | 3 | 共享连接的锁等待队列长度上限；过高会增加内存消耗 |
| `wait_timeout` | 1000-10000 ms | 2000 ms | 锁等待超时（全局，作用于所有 ModbusSource） |
| `io_pool_size` | 1-64 | 16 | modbus 阻塞事务（master.send 含写+等回音）的专职旁池 `ecat-modbus-io-0..N-1` 定容 |

```yaml
# integration-modbus.yml 示例
max_waiters: 3
wait_timeout: 2000
io_pool_size: 16
```

- **io_pool_size 建议**：源数（TCP 连接数 + 串口数）很大或事务时长偏长（慢设备/高重试）时上调；`system_health` 出现大量 `RejectedExecutionException` 拒绝日志即为扩容信号（池满时新事务立即失败，过期即弃，下周期再试）。

### 设备超时配置

- TCP 事务超时：ConfigEntry `comm_settings.timeout`（默认 2000ms）
- RTU 事务超时：ConfigEntry `serial_settings.timeout`（默认 500ms）
- 两种超时的职责边界与场景参考值：[docs/timeout-architecture.md](docs/timeout-architecture.md)

## 使用指南

### TCP 多设备共享连接

多个设备注册到同一网关（`ip:port` 相同即共享底层连接，slaveId 按设备区分）：

```java
ModbusTcpInfo tempInfo = new ModbusTcpInfo("192.168.1.100", 502, 1);   // 温度传感器 slaveId=1
ModbusTcpInfo pressureInfo = new ModbusTcpInfo("192.168.1.100", 502, 2); // 压力传感器 slaveId=2
// 两个 info 的 ip:port 相同 → 底层 TCP 连接复用；slaveId 各自生效（DeviceSpecificModbusSource）

ModbusSource tempSensor = modbus.register(tempInfo, "temp-sensor-001");
ModbusTransactionStrategy.executeWithLambda(tempSensor, source ->
    source.readHoldingRegisters(0, 2)
        .thenApply(response -> {
            if (!response.isException()) {
                short[] values = response.getShortData();
                double temperature = values[0] * 0.1; // 转换为实际温度
                System.out.println("温度: " + temperature + "°C");
                return true;
            }
            return false;
        }));

ModbusSource pressureSensor = modbus.register(pressureInfo, "pressure-sensor-001");
ModbusTransactionStrategy.executeWithLambda(pressureSensor, source ->
    source.readHoldingRegisters(10, 2)
        .thenApply(response -> {
            if (!response.isException()) {
                short[] values = response.getShortData();
                double pressure = values[0] * 0.01;
                System.out.println("压力: " + pressure + " MPa");
                return true;
            }
            return false;
        }));
```

### 串行总线（RTU）设备控制

```java
// 构造参数：portName, baudrate, dataBits, stopBits, parity, timeout(ms), slaveId
ModbusSerialInfo serialInfo = new ModbusSerialInfo(
    "COM3",
    9600,
    8,
    ModbusSerialInfo.ONE_STOP_BIT,
    ModbusSerialInfo.NO_PARITY,
    1000, // 事务超时1秒
    1     // slaveId
);

ModbusSource lightingControl = modbus.register(serialInfo, "lighting-control-001");
ModbusTransactionStrategy.executeWithLambda(lightingControl, source ->
    source.writeCoil(0, true)  // 开启照明
        .thenApply(response -> {
            if (!response.isException()) {
                System.out.println("照明控制成功");
                return true;
            }
            return false;
        }));

ModbusSource hvacControl = modbus.register(serialInfo, "hvac-control-001");
ModbusTransactionStrategy.executeWithLambda(hvacControl, source ->
    source.writeRegister(0, 220)  // 设置22.0°C
        .thenApply(response -> {
            if (!response.isException()) {
                System.out.println("温度设定成功");
                return true;
            }
            return false;
        }));
```

## 使用注意事项

### 1. 并发访问控制：必须经事务策略执行

**为什么必须使用锁机制？**

在多设备共享同一 Modbus 连接的环境中，直接调用 `source.readHoldingRegisters()` 等方法会导致严重的并发问题：

1. **异步数据混乱**: 多个线程同时访问同一连接时，数据包可能交错发送和接收
2. **响应错乱**: 设备A的响应可能被线程B误认为是自己的响应
3. **SlaveId 冲突**: 多个设备使用不同 slaveId 时，可能出现响应混淆
4. **连接状态不一致**: 并发操作可能导致连接状态管理混乱

**正确的做法：所有 Modbus 操作都必须通过 `ModbusTransactionStrategy.executeWithLambda` 执行**（周期采集走 `ModbusPolling`，其内部已持锁）。

```java
// ✅ 正确：使用策略锁包装所有操作
ModbusTransactionStrategy.executeWithLambda(modbusSource, source ->
    source.readHoldingRegisters(0, 10)
        .thenApply(response -> {
            if (!response.isException()) {
                short[] values = response.getShortData();
                // 处理数据...
                return true;
            }
            return false;
        }));

// ❌ 错误：直接调用会导致并发问题
source.readHoldingRegisters(0, 10)
    .thenAccept(response -> {
        // 这种方式在多设备环境下不安全！
    });
```

### 2. 错误处理

响应异常（从站返回异常码）与传输错误（超时/断线）经不同路径到达——前者在响应对象上，后者以异常完成 future：

```java
ModbusTransactionStrategy.executeWithLambda(modbusSource, source ->
    source.readHoldingRegisters(0, 10)
        .thenApply(response -> {
            if (response.isException()) {
                throw new IllegalStateException("从站异常响应: " + response.getExceptionMessage());
            }
            short[] values = response.getShortData();
            // ...解析数据
            return true;
        }))
    .exceptionally(ex -> {                 // 传输错误/锁等待失败经异常完成
        System.err.println("读取失败: " + ex.getMessage());
        return false;
    });
```

### 3. 资源与生命周期管理

```java
// 正确的注册方式：每个设备使用唯一 identity
ModbusSource source = modbus.register(modbusInfo, "unique-device-001");

// 设备移除时解除占用（最后一个占用者释放时底层连接销毁）
source.removeIntegration("unique-device-001");
```

集成自身的生命周期（onInit/onStart/onPause/onRelease）由 core 框架管理，消费仓不直接调用。

### 4. 性能优化建议

以下片段仅示意批量读 API 的形态；实际调用须经 `executeWithLambda` / `ModbusPolling` 持锁执行。

```java
// 推荐：批量读取减少网络往返
CompletableFuture<ReadHoldingRegistersResponse> future =
    source.readHoldingRegisters(0, 20); // 一次性读取20个寄存器

// 推荐：一轮事务内并行发起多段读（锁已由事务策略持有）
List<CompletableFuture<?>> futures = new ArrayList<>();
futures.add(source.readHoldingRegisters(0, 10));
futures.add(source.readInputRegisters(20, 5));
futures.add(source.readCoils(100, 8));
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
    .thenRun(() -> System.out.println("所有操作完成"));
```

## 依赖

### 消费方接入

在自己的集成 pom 中声明（scope=provided，运行时由宿主 core 装载本模块）：

```xml
<dependency>
    <groupId>com.ecat</groupId>
    <artifactId>integration-modbus</artifactId>
    <version>3.1.0</version>
    <scope>provided</scope>
</dependency>
```

### 底层库

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

## 构建

```bash
mvn clean install -f ecat-integrations/modbus/pom.xml
```

## 测试

```bash
mvn test -f ecat-integrations/modbus/pom.xml
```

关键测试类：
- `ModbusPollingSdkTest` / `ModbusPollingChainTest` — 轮询 SDK 契约与网格数学（fake 定时缝，确定性断言）
- `ModbusTransactionStrategyTest` / `ModbusTransactionStrategyHardTimeoutTest` — 事务策略与事务级硬超时
- `ModbusSlaveRegistryTest` / `ModbusTcpSlaveConfigTest` — Slave 注册与配置校验
- `ModbusWedgeRecoveryContractTest` / `ModbusApplyWedgeRecoveryTest` — 卡死恢复契约
- `EndianConverterSemanticsTest` / `FourOrderEndianConverterTest` — 字节序换算语义

**RTU Slave/Master 本地全链路验证**（无需真实设备）：

```bash
# 1. 创建虚拟串口对
sudo socat -d -d pty,raw,echo=0,link=/dev/ttyV0 pty,raw,echo=0,link=/dev/ttyV1
# 2. 先跑 Slave Demo（占用 /dev/ttyV0）
# 3. 再跑 Master Client Demo（占用 /dev/ttyV1）
```

对应 Demo：`src/test` 下 `SerialRtuSlaveServerDemo`（Slave 端，配 `SmartStationDeviceCallback` 模拟智慧站房设备）与 `SerialRtuMasterClientDemo`（Master 端）。

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
- 区分超时类型：锁等待超时（日志 `Acquire timeout`）vs 事务超时（日志 `ModbusTransportException`）
- 锁等待超时：增大集成配置 `wait_timeout` 或减少 `max_waiters`
- 事务超时：增大 ConfigEntry 中 `comm_settings.timeout`（TCP）或 `serial_settings.timeout`（RTU）
- 检查网络延迟和设备响应时间
- 检查是否有慢设备阻塞共享连接上的其他设备

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

### v3.0.0
- **对齐 ecat-core 3.0（state-sealing 重构）**：模块版本随 core 大版本对齐到 3.0.0。
- **`ModbusLinearConversionAttribute` 区分原始信号与工程值**：新增 `updateRawValue(short registerValue)`——原始寄存器值（如电压）写入 `rawValue`（不进 state、不持久化），由 `LinearConversionAttribute` 自动换算为工程值存入 `attr.value`（业务值，进 state、持久化）。**驱动开发者灌寄存器数据时应调 `updateRawValue`，不要直接 `updateValue`**（直接 updateValue 会绕过线性换算）。
- **总线事件载荷变更**：`device.data.update` 载荷改为 `DeviceDataChangedEvent`（含 old/new 两个不可变 `AttrState`），消费方读 newState，不再反查 live attr。

### v1.2.0
- TCP 模式事务超时连通：`ModbusTcpCommConfigSchema.timeout` 字段现在正确传递到 `modbusMaster.setTimeout()`
- `ModbusTcpInfo` 新增 `timeout` 字段和 5 参数构造函数
- `ModbusMasterFactory.createTcpMaster()` 新增 `setTimeout()` 调用
- 所有 TCP Modbus 子集成（22 个）更新为解析并传递 timeout 配置

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

## 相关文档

- [docs/timeout-architecture.md](docs/timeout-architecture.md) — 两种超时的职责边界、传递链路与场景参考值
- [docs/plans/2026-02-24-modbus-slave-design.md](docs/plans/2026-02-24-modbus-slave-design.md) — Slave 设计文档（**注意**：其中回调示例为旧签名——`onReadHoldingRegister` 返回 `byte[]`；现行签名返回 `short`，以 `Slave/ModbusDataCallback.java` 与本文「快速上手」节为准）
- [docs/MULTI_DEVICE_SOLUTION.md](docs/MULTI_DEVICE_SOLUTION.md) — 多设备共享连接（DeviceSpecificModbusSource）方案

## 许可证

本项目采用 Apache License 2.0，详见 LICENSE 文件。

## 协议声明
1. 核心依赖：本插件基于 **ECAT Core**（Apache License 2.0）开发，Core 项目地址：https://github.com/ecat-project/ecat-core。
2. 插件自身：本插件的源代码采用 [Apache License 2.0] 授权。
3. 合规说明：使用本插件需遵守 ECAT Core 的 Apache 2.0 协议规则，若复用 ECAT Core 代码片段，需保留原版权声明。

### 许可证获取
- ECAT Core 完整许可证：https://github.com/ecat-project/ecat-core/blob/main/LICENSE
- 本插件许可证：./LICENSE
