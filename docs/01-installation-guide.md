# Aeron Cluster 安装配置指南

## 1. 环境概览

| 项目 | 配置 |
|------|------|
| AWS Region | us-west-2 |
| VPC | vpc-0771006861a6a98ae |
| Subnet | subnet-066252a94b92b2b0a (us-west-2a) |
| 实例类型 | m7i.xlarge (4 vCPU, 16GB RAM) |
| OS | Amazon Linux 2023 |
| JDK | Amazon Corretto 17.0.18 |
| Aeron | 1.48.0 |
| Agrona | 2.2.1 |
| 构建工具 | Gradle 8.5 |

## 2. 节点信息

### 主集群 (Primary Cluster)
| 角色 | Instance ID | Private IP | Node ID | 端口范围 |
|------|------------|------------|---------|---------|
| Main Node 0 | i-07a63d88a1a57264a | 172.31.16.122 | 0 | 9001-9005 |
| Main Node 1 | i-040f5e51e896ca6da | 172.31.20.42 | 1 | 9101-9105 |
| Main Node 2 | i-0acddffdc381547db | 172.31.16.136 | 2 | 9201-9205 |

### Standby 集群 (Standby Cluster)
| 角色 | Instance ID | Private IP | Node ID | 端口范围 |
|------|------------|------------|---------|---------|
| Standby Node 0 | i-019c3063e74954152 | 172.31.18.179 | 0 | 9001-9005 |
| Standby Node 1 | i-0ab0be5c44d49b4ef | 172.31.17.148 | 1 | 9101-9105 |
| Standby Node 2 | i-0ab319dd72af0dcaf | 172.31.31.207 | 2 | 9201-9205 |

### 管理节点
| 用途 | IP | 实例类型 |
|------|-----|---------|
| 构建/客户端/管理 | 172.31.16.209 | t3.large |

## 3. 端口规划

采用 Aeron 官方 `PORTS_PER_NODE=100` 方案：

```
端口 = BASE_PORT(9000) + nodeId × 100 + offset
```

| 端口用途 | Offset | Node 0 | Node 1 | Node 2 | 说明 |
|---------|--------|--------|--------|--------|------|
| Archive Control | +1 | 9001 | 9101 | 9201 | 归档控制请求 |
| Client Ingress | +2 | 9002 | 9102 | 9202 | 客户端消息入口 |
| Consensus | +3 | 9003 | 9103 | 9203 | 节点间共识协议 |
| Log Replication | +4 | 9004 | 9104 | 9204 | Leader→Follower 日志复制 |
| Transfer/Catchup | +5 | 9005 | 9105 | 9205 | Follower 追赶恢复 |

### Cluster Members 字符串格式
```
nodeId,ingressEndpoint,consensusEndpoint,logEndpoint,transferEndpoint,archiveEndpoint|...
```
实际值：
```
0,172.31.16.122:9002,172.31.16.122:9003,172.31.16.122:9004,172.31.16.122:9005,172.31.16.122:9001|
1,172.31.20.42:9102,172.31.20.42:9103,172.31.20.42:9104,172.31.20.42:9105,172.31.20.42:9101|
2,172.31.16.136:9202,172.31.16.136:9203,172.31.16.136:9204,172.31.16.136:9205,172.31.16.136:9201|
```

## 4. 安全组配置

安全组名：`aeron-cluster-sg` (sg-07517c6f5f938158d)

| 规则类型 | 协议 | 端口 | 源 | 说明 |
|---------|------|------|-----|------|
| Inbound | TCP | 22 | 0.0.0.0/0 | SSH 访问 |
| Inbound | TCP | 0-65535 | sg-07517c6f5f938158d | 集群内部 TCP |
| Inbound | UDP | 0-65535 | sg-07517c6f5f938158d | 集群内部 UDP |
| Inbound | TCP/UDP | 0-65535 | sg-04646ef3f2c9b6db4 | 管理节点访问 |

> **说明**：Aeron 的 Archive Replication 使用动态端口（endpoint=host:0），所以需要放行全端口范围。

## 5. 软件安装步骤

### 5.1 JDK 17 安装（每台节点）

通过 user-data 脚本自动完成：
```bash
yum install -y java-17-amazon-corretto-devel
echo 'export JAVA_HOME=/usr/lib/jvm/java-17-amazon-corretto' >> /etc/profile.d/java.sh
echo 'export PATH=$JAVA_HOME/bin:$PATH' >> /etc/profile.d/java.sh
```

### 5.2 OS 调优（每台节点）

```bash
# /etc/sysctl.d/99-aeron.conf
net.core.rmem_max=2097152    # 接收缓冲区最大值 (2MB)
net.core.wmem_max=2097152    # 发送缓冲区最大值 (2MB)
net.core.rmem_default=2097152
net.core.wmem_default=2097152
```

```bash
sysctl --system
mount -o remount,size=2G /dev/shm   # 确保 /dev/shm 足够大
```

### 5.3 构建项目（管理节点）

```bash
# 安装 Gradle
curl -s "https://get.sdkman.io" | bash
source ~/.sdkman/bin/sdkman-init.sh
sdk install gradle 8.5

# 构建
cd /home/ec2-user/aeron-test
./gradlew build

# 输出 JARs:
# build/libs/aeron-cluster-demo-1.0.0-all.jar    (服务端)
# build/libs/aeron-cluster-client-1.0.0-all.jar  (客户端)
```

### 5.4 部署到所有节点

```bash
bash scripts/deploy-all.sh
```

## 6. 关键配置项详解

### 6.1 MediaDriver.Context

```java
new MediaDriver.Context()
    .aeronDirectoryName(aeronDirName)       // Aeron 共享内存目录
    .threadingMode(ThreadingMode.DEDICATED)  // 3个独立线程: sender, receiver, conductor
    .termBufferSparseFile(true)             // 稀疏文件，按需分配磁盘空间
    .multicastFlowControlSupplier(          // 最小流控：所有接收者确认后才推进
        new MinMulticastFlowControlSupplier())
    .dirDeleteOnStart(true);                // 启动时清理旧目录
```

**threadingMode 说明**：
- `DEDICATED`：3 个独立线程，最低延迟，CPU 占用高
- `SHARED`：1 个线程处理所有，省资源，延迟较高
- `SHARED_NETWORK`：sender/receiver 共享，conductor 独立

### 6.2 Archive.Context

```java
new Archive.Context()
    .controlChannel(udpChannel(...))          // Archive 控制通道（外部请求）
    .archiveClientContext(replicationCtx)      // 用于 Archive 间复制的客户端上下文
    .localControlChannel("aeron:ipc?...")      // 本地 IPC 控制通道（ConsensusModule 使用）
    .replicationChannel("aeron:udp?endpoint=host:0")  // 复制通道（动态端口）
    .recordingEventsEnabled(false)            // 关闭录制事件（生产推荐）
    .threadingMode(ArchiveThreadingMode.SHARED)
```

**replicationChannel**：Aeron 1.33.0+ 必须设置。用于 Archive 间日志复制，使用动态端口(`:0`)。

### 6.3 AeronArchive.Context

```java
new AeronArchive.Context()
    .lock(NoOpLock.INSTANCE)                  // 无锁（单线程访问）
    .controlRequestChannel(archive.localControlChannel())   // IPC 控制请求
    .controlRequestStreamId(archive.localControlStreamId()) // 流 ID
    .controlResponseChannel(archive.localControlChannel())  // IPC 控制响应
```

**为什么用 NoOpLock**：ConsensusModule 和 ServiceContainer 各自持有独立的 AeronArchive 客户端实例，不存在并发访问，所以无需加锁。

### 6.4 ConsensusModule.Context

```java
new ConsensusModule.Context()
    .clusterMemberId(nodeId)                // 节点唯一标识
    .clusterMembers(clusterMembers)         // 全部成员端点列表
    .clusterDir(new File(...))              // 集群持久化目录
    .archiveContext(aeronArchiveCtx.clone()) // Archive 客户端（必须 clone）
    .serviceCount(1)                        // 服务数量
    .ingressChannel("aeron:udp")            // 客户端入口通道模板
    .replicationChannel("aeron:udp?endpoint=host:0")
    .deleteDirOnStart(true)                 // 首次启动清理
```

**ingressChannel**：设为 `"aeron:udp"`，不带 endpoint 参数。ConsensusModule 会自动从 clusterMembers 中提取本节点的 ingress endpoint 并拼接。

### 6.5 ClusteredServiceContainer.Context

```java
new ClusteredServiceContainer.Context()
    .aeronDirectoryName(aeronDirName)
    .archiveContext(aeronArchiveCtx.clone()) // 必须 clone！
    .clusterDir(new File(...))              // 与 ConsensusModule 相同的目录
    .clusteredService(new EchoClusteredService())
    .serviceId(0)                           // 第一个服务为 0
```

**为什么 archiveContext 要 clone**：ConsensusModule 和 ServiceContainer 需要各自独立的 AeronArchive 连接，共享同一个会导致并发问题。

## 7. JVM 启动参数

```bash
java \
  -server \
  # JDK 17 模块系统开放（Agrona 需要）
  --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  # JVM 调优
  -XX:+UnlockDiagnosticVMOptions \
  -XX:GuaranteedSafepointInterval=300000 \  # 减少 safepoint 停顿（300s）
  -XX:+UseParallelGC \                      # 吞吐量优先 GC
  -Xms512m -Xmx2g \
  # 网络
  -Djava.net.preferIPv4Stack=true \         # 只用 IPv4
  # Aeron 性能
  -Dagrona.disable.bounds.checks=true \     # 关闭边界检查（生产优化）
  -Daeron.pre.touch.mapped.memory=true \    # 预触碰内存映射（减少首次延迟）
  -Daeron.socket.so_sndbuf=2m \             # Socket 发送缓冲区
  -Daeron.socket.so_rcvbuf=2m \             # Socket 接收缓冲区
  -Daeron.rcv.initial.window.length=2m      # 初始接收窗口
```

## 8. 启动与停止命令

### 启动主集群
```bash
# 所有 3 个节点需要同时（或接近同时）启动
# Node 0
ssh -i ~/.ssh/aeron-cluster ec2-user@172.31.16.122 \
  "nohup /opt/aeron/start-main-node.sh 0 172.31.16.122 172.31.20.42 172.31.16.136 > /opt/aeron/node.log 2>&1 &"

# Node 1
ssh -i ~/.ssh/aeron-cluster ec2-user@172.31.20.42 \
  "nohup /opt/aeron/start-main-node.sh 1 172.31.16.122 172.31.20.42 172.31.16.136 > /opt/aeron/node.log 2>&1 &"

# Node 2
ssh -i ~/.ssh/aeron-cluster ec2-user@172.31.16.136 \
  "nohup /opt/aeron/start-main-node.sh 2 172.31.16.122 172.31.20.42 172.31.16.136 > /opt/aeron/node.log 2>&1 &"
```

### 启动 Standby 集群
```bash
# Node 0
ssh -i ~/.ssh/aeron-cluster ec2-user@172.31.18.179 \
  "nohup /opt/aeron/start-standby-node.sh 0 172.31.18.179 172.31.17.148 172.31.31.207 172.31.16.122 172.31.20.42 172.31.16.136 > /opt/aeron/node.log 2>&1 &"

# Node 1, Node 2 类似...
```

### 停止集群
```bash
# 优雅停止（SIGTERM）
ssh -i ~/.ssh/aeron-cluster ec2-user@<IP> "kill \$(pgrep -f 'ClusterNode\|StandbyNode')"

# 强制停止（SIGKILL）
ssh -i ~/.ssh/aeron-cluster ec2-user@<IP> "kill -9 \$(pgrep -f 'ClusterNode\|StandbyNode')"
```

### 测试客户端
```bash
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  -Djava.net.preferIPv4Stack=true \
  -Daeron.cluster.ingress.endpoints="0=172.31.16.122:9002,1=172.31.20.42:9102,2=172.31.16.136:9202" \
  -Daeron.cluster.egress.endpoint="172.31.16.209:0" \
  -Daeron.cluster.message.count=10 \
  -Daeron.cluster.message.text=hello \
  -cp /opt/aeron/aeron-cluster-client-all.jar \
  com.example.aeron.client.ClusterClient
```

## 9. 常见问题排错

| 错误 | 原因 | 解决方案 |
|------|------|---------|
| `ShutdownSignalBarrier` IllegalAccessException | JDK 17 模块系统限制 | 添加 `--add-opens java.base/jdk.internal.misc=ALL-UNNAMED` |
| `Archive.Context.replicationChannel must be set` | Aeron 1.33.0+ 必须配置 | 添加 `.replicationChannel("aeron:udp?endpoint=host:0")` |
| `ingressChannel must be specified` | ConsensusModule 必须设置 | 添加 `.ingressChannel("aeron:udp")` |
| Ingress 端口未绑定 | 缺少 archiveContext 或 ingressChannel 带了额外参数 | ingressChannel 设为纯 `"aeron:udp"`，ConsensusModule 设置 archiveContext |
| Client 连接超时 | 安全组/端口不通 | 检查 SG 规则，确保 UDP 端口放行 |
| Agrona 版本不兼容 | aeron-all 1.48.0 依赖 Agrona 2.x | 使用 Agrona 2.2.1 |

## 10. 目录结构

```
/home/ec2-user/aeron-test/          # 项目根目录（管理节点）
├── build.gradle                     # Gradle 构建配置
├── settings.gradle
├── src/main/java/com/example/aeron/
│   ├── cluster/
│   │   ├── ClusterNode.java         # 主集群节点入口
│   │   ├── StandbyNode.java         # Standby 集群节点入口
│   │   └── EchoClusteredService.java # 业务逻辑实现
│   └── client/
│       └── ClusterClient.java        # 测试客户端
├── scripts/
│   ├── start-main-node.sh           # 启动主集群节点
│   ├── start-standby-node.sh        # 启动 Standby 节点
│   ├── run-client.sh                # 运行客户端
│   └── deploy-all.sh                # 部署到所有节点
└── docs/
    ├── 01-installation-guide.md      # 本文档
    ├── 02-communication-principles.md # 通讯原理
    ├── 03-benchmarking.md            # 压测文档
    ├── 04-optimization-guide.md      # 优化指南
    └── 05-fault-drill-guide.md       # 故障演练

/opt/aeron/                           # 每台集群节点的运行目录
├── aeron-cluster-demo-all.jar        # 服务端 fat jar
├── aeron-cluster-client-all.jar      # 客户端 fat jar
├── start-main-node.sh
├── start-standby-node.sh
├── run-client.sh
├── node.log                          # 节点日志
└── node{0,1,2}/                      # 节点数据目录
    ├── aeron/                        # MediaDriver 共享内存
    ├── archive/                      # Archive 录制文件
    └── cluster/                      # 集群状态（mark file, node-state）
```
