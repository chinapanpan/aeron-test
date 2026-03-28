# Aeron Cluster 通讯原理详解

## 1. Aeron 传输协议基础

### 1.1 通信模式

Aeron 支持三种通信模式：

| 模式 | 说明 | 使用场景 |
|------|------|---------|
| **UDP Unicast** | 点对点 UDP 通信 | AWS 云环境（不支持 multicast） |
| **UDP Multicast** | 组播通信 | 局域网环境 |
| **IPC** | 进程间通信（内存映射文件） | 同节点内组件间通信 |

本集群采用 **UDP Unicast**（节点间）+ **IPC**（节点内组件间）。

### 1.2 协议帧结构

所有 Aeron 帧以通用头部开始：

```
+-------+-------+-------+-------+
| Frame Length (31 bits) | Ver   |
+-------+-------+-------+-------+
| Flags |    Frame Type          |
+-------+-------+-------+-------+
```

关键帧类型：

| 帧类型 | 代码 | 说明 |
|--------|------|------|
| DATA | 0x0001 | 数据帧，携带应用消息 |
| NAK | 0x0002 | 否定确认，请求重传丢失的数据 |
| Status Message (SM) | 0x0003 | 状态消息，接收端发送，用于流控 |
| SETUP | 0x0005 | 建立连接 |
| RTTM | 0x0006 | 往返时间测量 |

### 1.3 流控机制

Aeron 采用**接收端驱动**的流控模型：

```
发送端 (Sender)                        接收端 (Receiver)
    |                                       |
    |  --- DATA frame (term=0, offset=0) -->|
    |  --- DATA frame (term=0, offset=1) -->|
    |                                       |
    |  <-- Status Message (SM) ------------|  (告知发送端：我收到了 offset=1，
    |      (completed=1, window=128KB)      |   接收窗口还有 128KB)
    |                                       |
    |  --- DATA frame (term=0, offset=2) -->|
    |  ...                                  |
```

- **接收窗口（Receiver Window）**：由 SM 中的 `window` 字段控制
- **初始窗口**：= min(term_length/4, 初始值)，本集群设为 2MB
- **发送端只能发送到窗口边界**，防止淹没接收端

### 1.4 丢包恢复

Aeron 使用 **NAK（Negative Acknowledgement）** 机制：

```
发送端                                   接收端
    |                                       |
    |  --- DATA #1 ----------------------->|
    |  --- DATA #2 ----X (丢失)            |
    |  --- DATA #3 ----------------------->|
    |                                       |  检测到 gap：收到 #3 但没有 #2
    |  <-- NAK (request #2) ---------------|
    |                                       |
    |  --- DATA #2 (retransmit) ---------->|  重传
    |                                       |
```

- 接收端检测到数据间隙后**立即发送 NAK**（unicast）
- 发送端收到 NAK 后**重传指定范围的数据**
- NAK 有去重机制，短时间内不会重复发送同一 NAK

### 1.5 心跳机制

```
发送端                                   接收端
    |                                       |
    |  --- DATA (real message) ----------->|
    |  ... (no data for a while) ...        |
    |  --- DATA (length=0, heartbeat) ---->|  零长度 DATA 帧
    |                                       |  维持连接活性
    |                                       |  检测尾部丢失
```

零长度 DATA 帧用于：
1. 维持流（stream）的存活状态
2. 允许接收端检测"尾部丢失"（最后几个帧丢了但没有后续帧触发 NAK）

---

## 2. Raft 共识算法在 Aeron 中的实现

### 2.1 Raft 核心概念

```
                    ┌─────────────┐
                    │   Leader    │  (只有一个)
                    │  写入日志   │
                    │  复制给     │
                    │  Followers  │
                    └──────┬──────┘
                           │
              ┌────────────┼────────────┐
              │            │            │
        ┌─────┴─────┐┌─────┴─────┐┌─────┴─────┐
        │ Follower 0 ││ Follower 1 ││ Follower 2 │
        │ 接收日志   ││ 接收日志   ││ 接收日志   │
        │ 确认       ││ 确认       ││ 确认       │
        └───────────┘└───────────┘└───────────┘
```

| 概念 | 说明 |
|------|------|
| **Term** | 任期编号，每次选举递增 |
| **Leader** | 唯一的写入者，负责日志复制 |
| **Follower** | 从 Leader 接收日志 |
| **Quorum** | 多数派：(n/2)+1。3 节点集群 quorum=2 |
| **CommitPosition** | 已被 quorum 确认的日志位置 |
| **AppendPosition** | 每个 Follower 已接收的日志位置 |

### 2.2 Leader 选举过程

```
时间轴 →

Node 0: [Follower] ──timeout──> [Candidate] ──vote request──> [Leader] ──heartbeat──>
Node 1: [Follower] ──────────────────────── vote ────────────> [Follower]
Node 2: [Follower] ──────────────────────── vote ────────────> [Follower]

         ├── 心跳超时 ──┤├── 投票阶段 ──┤├── 选举完成 ──┤
         │  5s (默认)   ││  1s (默认)   ││              │
```

选举步骤：
1. **Startup Canvass**：启动时所有节点广播自己的状态（5s 超时）
2. **心跳超时**：Follower 在 `leaderHeartbeatTimeout`(5s) 内没收到 Leader 心跳
3. **发起投票**：超时的节点递增 term，向其他节点请求投票
4. **投票规则**：每个 term 每个节点只能投一票，投给日志最新的 candidate
5. **选举成功**：获得 quorum 票数后成为 Leader
6. **心跳广播**：新 Leader 立即开始发送心跳（间隔 200ms）

### 2.3 日志复制过程

```
Client 消息 → Leader 的 IngressAdapter
                    │
                    ▼
             LogPublisher (写入日志)
                    │
          ┌─────────┼─────────┐
          ▼         ▼         ▼
     Leader      Follower  Follower
     Archive     Archive   Archive
     (本地)     (复制)     (复制)
          │         │         │
          │    appendPos  appendPos
          │         │         │
          └────┬────┘         │
               │              │
          quorum ack ─────────┘
               │
               ▼
         commitPosition 推进
               │
               ▼
     ClusteredServiceAgent
     (处理到 commitPosition)
               │
               ▼
        业务逻辑执行
```

关键点：
- **Leader 先写本地 Archive，再复制给 Followers**
- **Follower 通过 Archive Replication 从 Leader 拉取日志**
- **CommitPosition 只在 quorum 确认后才推进**
- **业务逻辑只处理到 commitPosition 的消息**

---

## 3. Aeron Cluster 四大组件通讯

### 3.1 组件架构图

```
┌────────────────────────────────────────────────────────────┐
│                    Aeron Cluster Node                       │
│                                                             │
│  ┌──────────────┐    ┌──────────────┐    ┌───────────────┐ │
│  │              │    │              │    │               │ │
│  │  MediaDriver │◄──►│   Archive    │◄──►│  Consensus    │ │
│  │              │    │              │    │  Module       │ │
│  │ ┌──────────┐│    │ ┌──────────┐ │    │               │ │
│  │ │ Sender   ││    │ │Recordings│ │    │ ┌───────────┐ │ │
│  │ │ Receiver ││    │ │ Segment  │ │    │ │ Ingress   │ │ │
│  │ │Conductor ││    │ │  Files   │ │    │ │ Adapter   │ │ │
│  │ └──────────┘│    │ └──────────┘ │    │ │ Log Pub   │ │ │
│  │              │    │              │    │ │ Consensus │ │ │
│  └──────────────┘    └──────────────┘    │ │  Pub/Adap │ │ │
│                                          │ └───────────┘ │ │
│                                          └───────┬───────┘ │
│                                                  │ IPC     │
│                                          ┌───────┴───────┐ │
│                                          │  Clustered    │ │
│                                          │  Service      │ │
│                                          │  Container    │ │
│                                          │               │ │
│                                          │ ┌───────────┐ │ │
│                                          │ │ Bounded   │ │ │
│                                          │ │ LogAdapter│ │ │
│                                          │ │ (spy sub) │ │ │
│                                          │ │           │ │ │
│                                          │ │ Your      │ │ │
│                                          │ │ Service   │ │ │
│                                          │ └───────────┘ │ │
│                                          └───────────────┘ │
└────────────────────────────────────────────────────────────┘
```

### 3.2 MediaDriver（媒体驱动）

负责所有网络 I/O 和进程间通信：

| 线程 | 职责 |
|------|------|
| **Sender** | 发送 DATA 帧，处理 NAK 重传 |
| **Receiver** | 接收 DATA/SM/NAK 帧 |
| **Conductor** | 管理通道生命周期，处理控制命令 |

DEDICATED 模式下 3 个线程独立运行，最低延迟。

### 3.3 Archive（归档）

- **持久化 Raft 日志**：所有消息都录制到 segment 文件
- **支持回放**：新加入的节点通过回放日志追赶状态
- **支持复制**：Follower 从 Leader 的 Archive 复制日志

### 3.4 ConsensusModule（共识模块）

内部子组件：

| 子组件 | 职责 |
|--------|------|
| **IngressAdapter** | 接收客户端消息（UDP subscription on ingress port） |
| **ConsensusModuleAgent** | 核心代理：管理选举、共识、日志 |
| **LogPublisher** | Leader 将消息写入日志 |
| **LogAdapter** | Follower 接收复制的日志 |
| **ConsensusPublisher** | 发送 CommitPosition/AppendPosition 等共识事件 |
| **ConsensusAdapter** | 接收和解码共识通道消息 |

### 3.5 ClusteredServiceContainer（服务容器）

| 子组件 | 职责 |
|--------|------|
| **BoundedLogAdapter** | 通过 spy subscription 读取日志，但只处理到 commitPosition |
| **ClusteredServiceAgent** | 轮询日志消息，调用用户的 ClusteredService 回调 |

**Spy Subscription**：不创建新的网络连接，而是直接读取同一个 MediaDriver 中已有的日志 term buffer。零拷贝，无额外网络开销。

---

## 4. 消息完整流转路径

从客户端发送到接收响应的完整路径：

```
Step 1:  Client → AeronCluster.offer(buffer)
            │
            ▼ (UDP publication to leader's ingress endpoint)
Step 2:  Leader MediaDriver (Receiver thread)
            │
            ▼ (shared memory)
Step 3:  ConsensusModule → IngressAdapter (subscription poll)
            │
            ▼
Step 4:  LogPublisher → write to log (term buffer)
            │
            ├──────────────────────────────────────┐
            ▼                                       ▼
Step 5:  Leader Archive                      Follower Archives
         (record locally)                    (replicate from leader)
            │                                       │
            │                                  appendPosition
            │                                       │
            ▼◄──────────────────────────────────────┘
Step 6:  Leader checks: appendPosition from quorum?
            │ Yes
            ▼
Step 7:  commitPosition 推进
            │
            ▼ (spy subscription on same term buffer)
Step 8:  BoundedLogAdapter (all nodes, bounded by commitPosition)
            │
            ▼
Step 9:  ClusteredServiceAgent → onSessionMessage(session, ts, buffer, ...)
            │
            ▼ (your business logic)
Step 10: session.offer(responseBuffer) → egress publication
            │
            ▼ (UDP to client's egress endpoint)
Step 11: Client → EgressListener.onMessage()
```

### 延迟分析

| 阶段 | 估计延迟 |
|------|---------|
| Client → Leader ingress (UDP) | ~0.2ms (同 AZ) |
| IngressAdapter → LogPublisher | ~0.01ms (IPC) |
| Log → Archive recording | ~0.01ms |
| Leader → Follower replication | ~0.3ms (UDP) |
| Quorum ack → commitPosition | ~0.3ms |
| commitPosition → service execution | ~0.01ms |
| Service → egress → Client | ~0.2ms |
| **总计（理论最优）** | **~1ms** |

实际测试中平均 7.3ms，差异来自：客户端同步等待、BackoffIdleStrategy 退避延迟、JIT 编译等。

---

## 5. 端口与通道详解

### 本集群实际通道配置

| 端口 | 通道类型 | 方向 | 说明 |
|------|---------|------|------|
| 9x01 | Archive Control | 外部→Archive | 其他节点请求 Archive 操作（复制等） |
| 9x02 | Client Ingress | Client→Leader | 客户端消息进入集群 |
| 9x03 | Consensus | Node↔Node | Raft 共识协议（投票、心跳、commit/append position） |
| 9x04 | Log | Leader→Followers | 日志复制通道 |
| 9x05 | Transfer/Catchup | Leader→Follower | 节点恢复时的快速追赶 |

（x = nodeId，如 Node 1 的端口为 91xx）

---

## 6. 选举过程详解

### 正常启动选举

```
时间  Node 0          Node 1          Node 2
 0s   [启动]          [启动]          [启动]
      canvass →       canvass →       canvass →
      (广播状态)      (广播状态)      (广播状态)

 5s   canvass超时      canvass超时      canvass超时
      比较日志        比较日志        比较日志
      位置...         位置...         位置...

 6s   [投票给X]       [发起选举]      [投票给X]
                      term++
                      请求投票 →

 7s   [Follower]      [Leader!]       [Follower]
                      开始发送心跳
                      间隔: 200ms
```

### Leader 故障后的选举

```
时间  Node 0          Node 1(Leader)  Node 2
 0s   [Follower]      [Leader]        [Follower]
      收到心跳 ✓      发送心跳        收到心跳 ✓

 1s   收到心跳 ✓      ☠ 进程被杀      收到心跳 ✓

 2-5s 没收到心跳...                   没收到心跳...

 6s   超时!                           超时!
      (heartbeatTimeout=5s)           发起选举
                                      term++

 7s   投票给 Node2                    获得 quorum
      [Follower]                      [Leader!]
                                      开始心跳
```

---

## 7. 确定性要求

Aeron Cluster 要求所有节点执行相同的日志得到**完全相同的状态**。这意味着 `ClusteredService` 中的业务逻辑必须是**确定性的**。

### 常见违反确定性的操作

| 违规操作 | 问题 | 解决方案 |
|---------|------|---------|
| `System.currentTimeMillis()` | 各节点时钟不同 | 使用 `timestamp` 参数（集群提供） |
| `Math.random()` | 各节点结果不同 | 使用消息中的种子 |
| `HashMap.keySet().forEach()` | 迭代顺序不确定 | 使用 `TreeMap` 或 Agrona 有序集合 |
| 读取本地配置文件 | 各节点文件可能不同 | 通过集群消息注入配置 |
| 网络请求（HTTP等） | 结果不确定 | 在集群外处理，结果作为消息发入集群 |

### 正确示例

```java
@Override
public void onSessionMessage(
    ClientSession session, long timestamp, DirectBuffer buffer, ...) {

    // ✓ 使用集群提供的 timestamp
    long eventTime = timestamp;

    // ✗ 不要使用 System.currentTimeMillis()
    // long eventTime = System.currentTimeMillis();

    // ✓ 使用 TreeMap 保证有序
    TreeMap<String, Integer> orderedMap = new TreeMap<>();

    // ✗ 不要使用 HashMap
    // HashMap<String, Integer> map = new HashMap<>();
}
```

---

## 8. 快照机制

### 为什么需要快照

没有快照时，恢复一个节点需要从**第一条消息开始回放所有日志**。消息越多，恢复越慢。

快照 = **某一时刻的完整状态**，恢复时只需从最近的快照开始回放。

```
完整日志: [msg1][msg2][msg3]...[msg1000000]
                                    ↑
                               快照点 (包含完整状态)
                                    ↓
恢复: [snapshot] → [msg1000001][msg1000002]...
      很快！        只需回放少量消息
```

### 快照流程

```java
@Override
public void onTakeSnapshot(ExclusivePublication snapshotPublication) {
    // 将完整状态序列化到 snapshotPublication
    buffer.putLong(0, messageCount);

    while (snapshotPublication.offer(buffer, 0, Long.BYTES) < 0) {
        cluster.idleStrategy().idle();  // 处理背压
    }
}

@Override
public void onStart(Cluster cluster, Image snapshotImage) {
    if (snapshotImage != null) {
        // 从快照恢复状态
        snapshotImage.poll((buffer, offset, length, header) -> {
            messageCount = buffer.getLong(offset);
        }, 1);
    }
}
```

### 快照触发时机

- ConsensusModule 周期性触发（可配置）
- 手动触发（通过管理 API）
- 推荐频率：根据消息量，每 30 分钟到每天一次
