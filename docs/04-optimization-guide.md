# Aeron Cluster 性能优化指南

本文档提供从 JVM、Aeron 参数、操作系统到架构层面的全方位优化建议，帮助提升 Aeron Cluster 的吞吐量和降低延迟。

---

## 1. 当前配置基线

| 配置项 | 当前值 |
|-------|--------|
| 线程模型 | DEDICATED |
| JDK 版本 | Amazon Corretto JDK 17 |
| Term Buffer 大小 | 16MB（默认值） |
| MTU 长度 | 1408 bytes |
| Socket 发送缓冲区 | 2MB |
| Socket 接收缓冲区 | 2MB |
| 基准性能 | 135 msg/sec（同步客户端） |

当前基准使用同步客户端模式，即客户端发送一条消息后等待响应再发送下一条。该模式下 135 msg/sec 主要受限于网络往返时延（RTT）。

---

## 2. JVM 层面优化

### 2.1 GC 优化

**当前配置：** 使用 `UseParallelGC`（JDK 17 默认）

**推荐方案：**

- **ZGC（推荐）：** 适用于低延迟场景，GC 停顿时间通常在亚毫秒级别
  ```
  -XX:+UseZGC
  ```
- **Shenandoah：** 另一个低延迟 GC 选项，与 ZGC 类似
  ```
  -XX:+UseShenandoahGC
  ```

**其他 GC 相关参数：**

- `GuaranteedSafepointInterval`：已设置为 300 秒，减少不必要的安全点停顿，这是正确的做法
- 关闭偏向锁（JDK < 18）：偏向锁在高并发场景下可能导致额外的安全点停顿
  ```
  -XX:-UseBiasedLocking
  ```
  > 注：JDK 18+ 已默认移除偏向锁，无需此参数。

### 2.2 JIT 优化

- **预分配堆内存：** 避免运行时页面错误（page fault）
  ```
  -XX:+AlwaysPreTouch
  ```
- **压缩指针：** 减少 64 位 JVM 的内存占用（堆 < 32GB 时有效）
  ```
  -XX:+UseCompressedOops
  ```
- **JIT 预热（Warmup）：** 在正式处理业务前，发送足够数量的预热消息以触发 JIT C2 编译器优化热点代码。建议预热至少 10,000 次调用以确保关键路径被编译为本机代码。

**完整推荐 JVM 参数示例：**

```bash
java \
  -XX:+UseZGC \
  -XX:+AlwaysPreTouch \
  -XX:+UseCompressedOops \
  -XX:-UseBiasedLocking \
  -XX:GuaranteedSafepointInterval=300000 \
  -XX:+UseLargePages \
  -Xms4g -Xmx4g \
  -jar your-application.jar
```

---

## 3. Aeron 参数优化

### 3.1 Media Driver

| 参数 | 当前值 | 推荐值 | 说明 |
|------|--------|--------|------|
| `threadingMode` | DEDICATED | DEDICATED | 已是最优选择，每个功能独立线程 |
| `sender.idle.strategy` | 默认 | BusySpinIdleStrategy | 最低延迟，但 CPU 占用 100% |
| `receiver.idle.strategy` | 默认 | BusySpinIdleStrategy | 最低延迟，但 CPU 占用 100% |
| `term.buffer.length` | 16MB | 按需调整 | 小消息用 64KB，大消息保持 16MB |
| `mtu.length` | 1408 | 8KB（需 jumbo frames） | 标准网络保持 1408 |
| `socket.so_rcvbuf` | 2MB | 2MB | 已设置，可根据需要增到 4MB |
| `socket.so_sndbuf` | 2MB | 2MB | 已设置，可根据需要增到 4MB |
| `rcv.initial.window.length` | 2MB | 2MB | 已设置 |
| `pre.touch.mapped.memory` | 已开启 | 已开启 | 预分配内存映射文件，减少运行时延迟 |

**Idle Strategy 选择指南：**

| 策略 | 延迟 | CPU 占用 | 适用场景 |
|------|------|---------|---------|
| BusySpinIdleStrategy | 最低 | 100% | 超低延迟，独占 CPU 核 |
| YieldingIdleStrategy | 低 | 高 | 低延迟，允许少量上下文切换 |
| BackoffIdleStrategy | 中 | 中 | 平衡延迟和 CPU 使用 |
| SleepingMillisIdleStrategy | 高 | 低 | 节能，延迟不敏感 |

**Term Buffer 大小选择：**

- `term.buffer.length` 必须是 2 的幂次方，范围 64KB - 1GB
- 小消息（< 1KB）：64KB 即可，减少内存占用
- 中等消息（1KB - 100KB）：1MB - 16MB
- 大消息（> 100KB）：16MB 或更大
- 每个 publication 占用 `term.buffer.length * 3` 的内存

### 3.2 Archive

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `segmentFileLength` | 256MB | 每个归档段文件的大小，可根据存储性能调整 |
| `fileSyncLevel` | 0 | 0 = 不调用 fsync，性能最高；1+ = 调用 fsync，保证持久性 |

**`fileSyncLevel` 选择：**

- **0（无 fsync）：** 最高性能，但断电时可能丢失数据。适用于可以通过集群副本恢复的场景。
- **1（数据 fsync）：** 每次写入后同步数据，保证数据持久性。
- **2（数据 + 元数据 fsync）：** 最高持久性保证，性能开销最大。

### 3.3 Consensus Module

| 参数 | 默认值 | 推荐值 | 说明 |
|------|--------|--------|------|
| `leaderHeartbeatTimeout` | 5s | 2s | 减小可加快故障检测速度 |
| `leaderHeartbeatInterval` | 200ms | 100ms | 减小可更频繁地发送心跳 |
| `electionTimeout` | 1s | 1s | 选举超时，需大于心跳间隔 |
| `sessionTimeout` | 默认 | 按业务需求 | 客户端会话超时时间 |

> 注意：减小心跳超时和间隔可以加快故障检测，但会增加网络开销。在不稳定网络中过小的超时可能导致误判 leader 故障，触发不必要的选举。

---

## 4. OS 层面优化

### 4.1 CPU 优化

#### CPU 隔离（isolcpus）

为 Media Driver 线程隔离专用 CPU 核，避免操作系统调度器将其他任务调度到这些核上：

```bash
# 在 GRUB 配置中添加内核参数（以隔离 CPU 2-5 为例）
GRUB_CMDLINE_LINUX="isolcpus=2-5"

# 使用 taskset 将 Media Driver 绑定到隔离的核
taskset -c 2-5 java -jar media-driver.jar
```

#### IRQ 亲和性

将网络中断绑定到非 Aeron 使用的 CPU 核，避免中断干扰 Aeron 线程：

```bash
# 查看网卡中断号
cat /proc/interrupts | grep eth0

# 设置中断亲和性到 CPU 0-1
echo 3 > /proc/irq/<IRQ_NUM>/smp_affinity
```

#### NUMA 感知

使用 `numactl` 将进程绑定到单个 NUMA 节点，避免跨 NUMA 访问内存带来的延迟：

```bash
# 绑定到 NUMA 节点 0
numactl --cpunodebind=0 --membind=0 java -jar media-driver.jar
```

### 4.2 内存优化

#### 大页内存（Huge Pages）

大页（2MB）可显著减少 TLB（Translation Lookaside Buffer）缓存未命中：

```bash
# 配置大页数量（例如分配 2048 个 2MB 大页 = 4GB）
echo 2048 > /proc/sys/vm/nr_hugepages
# 或永久配置
sysctl -w vm.nr_hugepages=2048
```

JVM 参数启用大页：

```
-XX:+UseLargePages
```

#### /dev/shm 大小

Aeron 使用共享内存（/dev/shm）进行 IPC，确保其大小足够：

```bash
# 查看当前大小
df -h /dev/shm

# 默认是 RAM 的 50%，可通过 /etc/fstab 调整
# tmpfs /dev/shm tmpfs defaults,size=8G 0 0
```

### 4.3 网络优化

#### Socket 缓冲区

```bash
# 查看当前值
sysctl net.core.rmem_max
sysctl net.core.wmem_max

# 已设置为 2MB，可考虑增大到 4MB
sysctl -w net.core.rmem_max=4194304
sysctl -w net.core.wmem_max=4194304
sysctl -w net.core.rmem_default=2097152
sysctl -w net.core.wmem_default=2097152
```

#### RSS（Receive Side Scaling）

多队列网卡优化，将不同的网络流分配到不同的 CPU 核处理：

```bash
# 查看网卡队列数
ethtool -l eth0

# 设置队列数
ethtool -L eth0 combined 4
```

#### Ring Buffer 大小

增大网卡接收/发送环形缓冲区，减少丢包：

```bash
# 查看当前设置
ethtool -g eth0

# 增大 ring buffer
ethtool -G eth0 rx 4096 tx 4096
```

#### Checksum Offload

如果不需要硬件校验和卸载，可以关闭以减少延迟：

```bash
# 查看当前 offload 设置
ethtool -k eth0

# 关闭校验和卸载
ethtool -K eth0 rx off tx off
```

### 4.4 磁盘优化

#### EBS 存储选型

| 存储类型 | IOPS | 吞吐量 | 延迟 | 适用场景 |
|---------|------|--------|------|---------|
| gp3 | 3,000 - 16,000 | 125 - 1,000 MB/s | 中 | 通用，Archive 存储 |
| io2 | 最高 64,000 | 最高 1,000 MB/s | 低 | 对延迟敏感的 Archive |
| io2 Block Express | 最高 256,000 | 最高 4,000 MB/s | 最低 | 高性能 Archive |
| Instance Store (NVMe) | 极高 | 极高 | 最低 | 最高性能，但数据不持久 |

**建议：**

- **gp3：** IOPS 和吞吐量可独立配置，性价比最高。适合大多数场景。
- **io2 / io2 Block Express：** 适合对 Archive 写入延迟有严格要求的场景。
- **Instance Store (NVMe)：** 适合追求极致性能且可以接受实例停止后数据丢失的场景。可结合 EBS 做定期备份。

---

## 5. 客户端优化

### 5.1 异步 Pipeline

当前同步模式（发送 -> 等待响应 -> 发送下一条）受限于 RTT，是吞吐量瓶颈的主要原因。

**优化方案：** 采用异步 pipeline 模式，不等待响应就发送下一条消息：

```java
// 伪代码示例
for (int i = 0; i < messageCount; i++) {
    // 异步发送，不等待响应
    while (publication.offer(buffer) < 0) {
        idleStrategy.idle();
    }
}

// 在单独的线程或回调中处理响应
egressListener.onMessage(/* 处理响应 */);
```

预期收益：吞吐量可提升 10-100 倍。

### 5.2 消息批处理（Batching）

将多条小消息合并为一条大消息发送，减少网络往返次数：

- 将多个业务请求打包到一个消息中
- 使用自定义的批处理协议
- 在延迟和吞吐量之间取平衡

### 5.3 消息对象池（Message Pooling）

预分配消息缓冲区，避免频繁创建和回收对象带来的 GC 压力：

```java
// 使用 DirectBuffer 池
DirectBuffer buffer = bufferPool.acquire();
try {
    // 编码消息
    encoder.encode(buffer, message);
    publication.offer(buffer);
} finally {
    bufferPool.release(buffer);
}
```

### 5.4 连接复用（Connection Pooling）

复用 `AeronCluster` 连接实例，避免频繁建立和断开连接的开销：

- 维护连接池
- 实现连接健康检查
- 支持自动重连

---

## 6. 架构层面优化

### 6.1 业务逻辑精简化

`onSessionMessage` 是集群处理消息的核心路径，必须尽可能快：

- **避免 I/O 操作：** 不要在 `onSessionMessage` 中进行文件读写、网络请求等阻塞操作
- **减少内存分配：** 使用预分配的缓冲区，避免在热路径上创建对象
- **简化计算：** 将复杂计算移到异步处理或预计算

### 6.2 Snapshot 策略

Snapshot 频率需要在恢复时间和 snapshot 开销之间取平衡：

- **频率过高：** 增加 CPU 和 I/O 开销，影响正常消息处理
- **频率过低：** 节点重启后需要重放大量日志，恢复时间长

**建议：**

- 按消息数量触发（例如每处理 100,000 条消息做一次 snapshot）
- 或按时间间隔触发（例如每 5 分钟做一次 snapshot）
- 在业务低峰期执行 snapshot

### 6.3 服务分离

考虑在集群中运行多个 `ClusteredService` 实例，将不同的业务逻辑分离：

- 将高频低延迟的服务与低频高计算量的服务分开
- 每个服务独立管理自己的状态
- 通过消息类型路由到不同的服务处理器

---

## 7. 监控与可观测性

### 7.1 AeronStat

Aeron 自带的实时监控工具，可查看关键指标：

```bash
# 运行 AeronStat
java -cp aeron-all.jar io.aeron.samples.AeronStat

# 或指定 CnC 文件目录
java -cp aeron-all.jar io.aeron.samples.AeronStat \
  -Daeron.dir=/path/to/aeron-dir
```

**关键指标：**

- `pub-pos`：发布位置
- `sub-pos`：订阅位置
- `snd-pos`：发送位置
- `rcv-hwm`：接收高水位
- `snd-lim`：发送限制
- `errors`：错误计数

### 7.2 Error Buffer 检查

定期检查 Aeron 错误缓冲区，发现潜在问题：

```bash
# 打印错误日志
java -cp aeron-all.jar io.aeron.samples.ErrorStat
```

### 7.3 Java Flight Recorder (JFR)

使用 JFR 进行深度性能分析：

```bash
# 启动时开启 JFR
java -XX:StartFlightRecording=duration=60s,filename=recording.jfr \
  -jar your-application.jar

# 运行时开启 JFR
jcmd <PID> JFR.start duration=60s filename=recording.jfr
```

分析重点：

- GC 停顿时间和频率
- 热点方法
- 线程阻塞和等待
- 内存分配热点

### 7.4 自定义监控指标

在 `ClusteredService` 中记录业务级别的指标：

```java
// 在 onSessionMessage 中记录处理延迟
long startTime = System.nanoTime();
// ... 处理消息 ...
long latencyNs = System.nanoTime() - startTime;
metrics.recordLatency(latencyNs);

// 记录消息计数
metrics.incrementMessageCount();

// 记录吞吐量
metrics.recordThroughput(messageSize);
```

建议通过 snapshot 持久化关键指标，或通过 side-channel 导出到外部监控系统（如 CloudWatch、Prometheus）。

---

## 8. EC2 实例升级路径

| 实例类型 | vCPU | 内存 | 网络带宽 | 适用场景 |
|---------|------|------|---------|---------|
| m7i.xlarge（当前） | 4 | 16GB | 12.5 Gbps | 学习/开发环境 |
| c7i.2xlarge | 8 | 16GB | 12.5 Gbps | 计算密集型，更多核给 Media Driver |
| c7i.4xlarge | 16 | 32GB | 12.5 Gbps | 高性能生产环境 |
| c7gn.xlarge | 4 | 8GB | 25 Gbps | 网络密集型，低网络延迟 |

**选型建议：**

- **开发/测试阶段：** `m7i.xlarge` 足够，4 核可满足 DEDICATED 模式（conductor + sender + receiver + 应用）
- **生产环境（计算密集）：** `c7i.2xlarge` 或 `c7i.4xlarge`，额外的核可以用于 CPU 隔离和 IRQ 亲和性设置
- **生产环境（网络密集）：** `c7gn.xlarge`，25 Gbps 网络带宽和更低的网络延迟，适合跨可用区部署的集群
- **Graviton（ARM）：** 如果应用兼容 ARM 架构，`c7g` 系列可提供更好的性价比

---

## 9. 优化优先级建议

根据投入产出比，推荐按以下顺序进行优化：

1. **客户端异步化**（高收益）：从同步改为异步 pipeline，预计吞吐量提升 10-100 倍
2. **GC 切换到 ZGC**（低投入）：仅需修改 JVM 参数，可显著降低尾部延迟
3. **OS 参数调优**（低投入）：网络缓冲区、大页内存等，一次配置长期受益
4. **Idle Strategy 优化**（中投入）：根据延迟要求选择合适的空闲策略
5. **CPU 隔离**（中投入）：需要更多 CPU 核，可能需要升级实例
6. **存储优化**（按需）：如果 Archive 写入成为瓶颈，升级 EBS 类型
7. **实例升级**（高投入）：当其他优化无法满足需求时，考虑升级实例类型
