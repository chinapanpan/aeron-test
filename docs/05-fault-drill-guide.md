# Aeron Cluster 故障演练指南

## 概述

本文档详细说明三种故障演练场景的操作步骤和预期结果。每个演练都包含：
- 演练前状态确认
- 故障注入方法
- 观察与验证
- 恢复步骤

## 环境信息

### 主集群
| 角色 | IP | Node ID | 当前状态 |
|------|-----|---------|---------|
| Main Node 0 (Follower) | 172.31.16.122 | 0 | Running |
| Main Node 1 (Leader) | 172.31.20.42 | 1 | Running |
| Main Node 2 (Follower) | 172.31.16.136 | 2 | Running |

### Standby 集群
| 角色 | IP | Node ID | 当前状态 |
|------|-----|---------|---------|
| Standby Node 0 (Leader) | 172.31.18.179 | 0 | Running |
| Standby Node 1 (Follower) | 172.31.17.148 | 1 | Running |
| Standby Node 2 (Follower) | 172.31.31.207 | 2 | Running |

### 管理节点
- IP: 172.31.16.209
- SSH Key: ~/.ssh/aeron-cluster

### 通用命令
```bash
# SSH 连接
SSH_OPTS="-i ~/.ssh/aeron-cluster -o StrictHostKeyChecking=no"

# 检查节点状态
ssh $SSH_OPTS ec2-user@<IP> "ps aux | grep '[C]luster'"

# 查看节点日志
ssh $SSH_OPTS ec2-user@<IP> "tail -20 /opt/aeron/node.log"

# 发送测试消息
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  -Djava.net.preferIPv4Stack=true \
  -Daeron.cluster.ingress.endpoints="0=172.31.16.122:9002,1=172.31.20.42:9102,2=172.31.16.136:9202" \
  -Daeron.cluster.egress.endpoint="172.31.16.209:0" \
  -Daeron.cluster.message.count=5 \
  -Daeron.cluster.message.text=test \
  -cp /opt/aeron/aeron-cluster-client-all.jar \
  com.example.aeron.client.ClusterClient
```

---

## 演练一：Leader 挂了

### 目的
验证当 Leader 节点故障时，集群是否能自动选举新 Leader 并继续服务。

### 原理
Aeron Cluster 基于 Raft 共识算法。当 Follower 在 `leaderHeartbeatTimeout`（默认 5s）内未收到 Leader 心跳，会发起新一轮选举。3 节点集群只需 2 个节点（quorum）即可完成选举。

### 步骤

#### Step 1: 确认当前状态
```bash
SSH_OPTS="-i ~/.ssh/aeron-cluster -o StrictHostKeyChecking=no"

# 发送测试消息，确认集群正常
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  -Djava.net.preferIPv4Stack=true \
  -Daeron.cluster.ingress.endpoints="0=172.31.16.122:9002,1=172.31.20.42:9102,2=172.31.16.136:9202" \
  -Daeron.cluster.egress.endpoint="172.31.16.209:0" \
  -Daeron.cluster.message.count=3 \
  -Daeron.cluster.message.text=pre-kill \
  -cp /opt/aeron/aeron-cluster-client-all.jar \
  com.example.aeron.client.ClusterClient

# 确认 Leader 是 Node 1 (172.31.20.42)
# 响应中应该显示 node=1, role=LEADER
```

#### Step 2: Kill Leader
```bash
# 模拟 Leader 突然故障（SIGKILL，不给进程优雅退出的机会）
ssh $SSH_OPTS ec2-user@172.31.20.42 "kill -9 \$(pgrep -f ClusterNode)"

# 确认进程已死
ssh $SSH_OPTS ec2-user@172.31.20.42 "ps aux | grep '[C]lusterNode'"
```

#### Step 3: 等待选举完成
```bash
# 等待约 5-10 秒（leaderHeartbeatTimeout=5s + electionTimeout=1s）
sleep 10

# 检查剩余两个节点的日志，查看谁成为新 Leader
ssh $SSH_OPTS ec2-user@172.31.16.122 "tail -5 /opt/aeron/node.log"
ssh $SSH_OPTS ec2-user@172.31.16.136 "tail -5 /opt/aeron/node.log"

# 预期：其中一个节点日志显示 "role changed to LEADER"
```

#### Step 4: 验证集群恢复
```bash
# 发送测试消息到集群（注意：Node 1 已死，客户端会自动发现新 Leader）
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  -Djava.net.preferIPv4Stack=true \
  -Daeron.cluster.ingress.endpoints="0=172.31.16.122:9002,1=172.31.20.42:9102,2=172.31.16.136:9202" \
  -Daeron.cluster.egress.endpoint="172.31.16.209:0" \
  -Daeron.cluster.message.count=3 \
  -Daeron.cluster.message.text=after-leader-kill \
  -cp /opt/aeron/aeron-cluster-client-all.jar \
  com.example.aeron.client.ClusterClient

# 预期：响应中 node 和 role=LEADER 指向新选举的 Leader
# messageCount 应该延续之前的计数（状态通过日志复制保持一致）
```

#### Step 5: 恢复被杀节点
```bash
# 重新启动 Node 1
ssh $SSH_OPTS ec2-user@172.31.20.42 \
  "nohup /opt/aeron/start-main-node.sh 1 172.31.16.122 172.31.20.42 172.31.16.136 > /opt/aeron/node.log 2>&1 &"

# 等待节点追赶日志
sleep 15

# 确认 Node 1 以 FOLLOWER 身份重新加入
ssh $SSH_OPTS ec2-user@172.31.20.42 "tail -5 /opt/aeron/node.log"
# 预期：显示 role=FOLLOWER
```

### 预期结果
- 选举在 ~6-10 秒内完成
- 新 Leader 能正常处理消息
- 消息计数器状态保持一致（不丢失）
- 恢复的节点自动作为 Follower 加入

---

## 演练二：Follower 挂了

### 目的
验证一个 Follower 故障时，集群是否继续正常运行（仍满足 quorum）。

### 原理
3 节点集群 quorum = 2。一个 Follower 故障后，Leader + 剩余 Follower 仍然构成 quorum，集群继续正常服务。但此时不能再容忍任何节点故障。

### 步骤

#### Step 1: 确认当前状态
```bash
# 发送测试消息确认集群正常
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  -Djava.net.preferIPv4Stack=true \
  -Daeron.cluster.ingress.endpoints="0=172.31.16.122:9002,1=172.31.20.42:9102,2=172.31.16.136:9202" \
  -Daeron.cluster.egress.endpoint="172.31.16.209:0" \
  -Daeron.cluster.message.count=3 \
  -Daeron.cluster.message.text=pre-follower-kill \
  -cp /opt/aeron/aeron-cluster-client-all.jar \
  com.example.aeron.client.ClusterClient
```

#### Step 2: Kill 一个 Follower
```bash
# Kill Node 0 (Follower)
ssh $SSH_OPTS ec2-user@172.31.16.122 "kill -9 \$(pgrep -f ClusterNode)"
echo "Node 0 (Follower) killed"
```

#### Step 3: 验证集群仍正常
```bash
# 无需等待选举，集群应该立即继续工作
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  -Djava.net.preferIPv4Stack=true \
  -Daeron.cluster.ingress.endpoints="0=172.31.16.122:9002,1=172.31.20.42:9102,2=172.31.16.136:9202" \
  -Daeron.cluster.egress.endpoint="172.31.16.209:0" \
  -Daeron.cluster.message.count=3 \
  -Daeron.cluster.message.text=after-follower-kill \
  -cp /opt/aeron/aeron-cluster-client-all.jar \
  com.example.aeron.client.ClusterClient

# 预期：消息仍由当前 Leader 正常处理
# Leader 和剩余 Follower 构成 quorum
```

#### Step 4: 恢复 Follower
```bash
ssh $SSH_OPTS ec2-user@172.31.16.122 \
  "nohup /opt/aeron/start-main-node.sh 0 172.31.16.122 172.31.20.42 172.31.16.136 > /opt/aeron/node.log 2>&1 &"

sleep 15
ssh $SSH_OPTS ec2-user@172.31.16.122 "tail -5 /opt/aeron/node.log"
# 预期：节点以 FOLLOWER 身份重新加入，通过日志回放追赶状态
```

### 预期结果
- 集群无中断，继续正常服务
- 不触发选举（Leader 仍然存活）
- 恢复的 Follower 通过日志回放追赶到最新状态

---

## 演练三：整个主集群挂了，由 Standby 接替

### 目的
模拟主集群完全不可用的灾难场景，验证 Standby 集群接管服务的能力。

### 原理
Standby 集群是一个独立的 Aeron Cluster Raft 组。当主集群完全失败时：
1. 停止 Standby 集群
2. 修改 Standby 配置，使其作为新的主集群启动
3. 客户端切换 ingress endpoints 到 Standby 集群的 IP

**注意**：当前实现中，Standby 集群是独立运行的 Raft 组，不自动从主集群复制数据。完整的 Standby 复制需要 Aeron 的 ClusterBackup 功能（商业版特性）。本演练演示手动切换过程。

### 步骤

#### Step 1: 确认两个集群都正常
```bash
# 测试主集群
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  -Djava.net.preferIPv4Stack=true \
  -Daeron.cluster.ingress.endpoints="0=172.31.16.122:9002,1=172.31.20.42:9102,2=172.31.16.136:9202" \
  -Daeron.cluster.egress.endpoint="172.31.16.209:0" \
  -Daeron.cluster.message.count=3 \
  -Daeron.cluster.message.text=primary-test \
  -cp /opt/aeron/aeron-cluster-client-all.jar \
  com.example.aeron.client.ClusterClient

# 测试 Standby 集群
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  -Djava.net.preferIPv4Stack=true \
  -Daeron.cluster.ingress.endpoints="0=172.31.18.179:9002,1=172.31.17.148:9102,2=172.31.31.207:9202" \
  -Daeron.cluster.egress.endpoint="172.31.16.209:0" \
  -Daeron.cluster.message.count=3 \
  -Daeron.cluster.message.text=standby-test \
  -cp /opt/aeron/aeron-cluster-client-all.jar \
  com.example.aeron.client.ClusterClient
```

#### Step 2: Kill 整个主集群
```bash
# 同时杀死主集群所有 3 个节点
ssh $SSH_OPTS ec2-user@172.31.16.122 "kill -9 \$(pgrep -f ClusterNode)" &
ssh $SSH_OPTS ec2-user@172.31.20.42 "kill -9 \$(pgrep -f ClusterNode)" &
ssh $SSH_OPTS ec2-user@172.31.16.136 "kill -9 \$(pgrep -f ClusterNode)" &
wait
echo "All primary cluster nodes killed"

# 确认所有节点已停止
for IP in 172.31.16.122 172.31.20.42 172.31.16.136; do
  echo "--- $IP ---"
  ssh $SSH_OPTS ec2-user@$IP "ps aux | grep '[C]lusterNode'" || echo "No process"
done
```

#### Step 3: 验证主集群不可用
```bash
# 尝试连接主集群（应该超时失败）
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  -Djava.net.preferIPv4Stack=true \
  -Daeron.cluster.ingress.endpoints="0=172.31.16.122:9002,1=172.31.20.42:9102,2=172.31.16.136:9202" \
  -Daeron.cluster.egress.endpoint="172.31.16.209:0" \
  -Daeron.cluster.message.count=1 \
  -Daeron.cluster.message.text=should-fail \
  -cp /opt/aeron/aeron-cluster-client-all.jar \
  com.example.aeron.client.ClusterClient

# 预期：连接超时异常
```

#### Step 4: 切换到 Standby 集群
```bash
# Standby 集群已经在运行，直接切换客户端的 ingress endpoints
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  -Djava.net.preferIPv4Stack=true \
  -Daeron.cluster.ingress.endpoints="0=172.31.18.179:9002,1=172.31.17.148:9102,2=172.31.31.207:9202" \
  -Daeron.cluster.egress.endpoint="172.31.16.209:0" \
  -Daeron.cluster.message.count=5 \
  -Daeron.cluster.message.text=failover-success \
  -cp /opt/aeron/aeron-cluster-client-all.jar \
  com.example.aeron.client.ClusterClient

# 预期：Standby 集群正常响应消息
# 注意：messageCount 从 Standby 集群自己的状态开始，不是主集群的延续
```

#### Step 5: 恢复主集群（可选）
```bash
# 重新启动主集群所有节点
for NODE in 0 1 2; do
  IPS=("172.31.16.122" "172.31.20.42" "172.31.16.136")
  ssh $SSH_OPTS ec2-user@${IPS[$NODE]} \
    "nohup /opt/aeron/start-main-node.sh $NODE 172.31.16.122 172.31.20.42 172.31.16.136 > /opt/aeron/node.log 2>&1 &" &
done
wait
sleep 15

# 检查主集群恢复状态
for IP in 172.31.16.122 172.31.20.42 172.31.16.136; do
  echo "=== $IP ==="
  ssh $SSH_OPTS ec2-user@$IP "tail -3 /opt/aeron/node.log"
done
```

### 预期结果
- 主集群完全不可用后，客户端切换 endpoints 到 Standby 集群
- Standby 集群独立运行，能正常处理请求
- 切换时间 = 检测时间 + 手动切换操作时间
- 主集群恢复后可以重新选举并正常运行

### 生产环境改进建议
1. **自动切换**: 使用 DNS 或负载均衡器实现自动 failover
2. **数据同步**: 使用 Aeron ClusterBackup (商业版) 实现 Standby 自动从主集群复制日志
3. **监控告警**: 设置 CloudWatch 告警监控集群节点健康状态
4. **定期演练**: 每月至少进行一次故障演练，确保流程熟练

---

## 故障演练检查清单

| 检查项 | 演练一 | 演练二 | 演练三 |
|--------|--------|--------|--------|
| 故障前集群正常 | [ ] | [ ] | [ ] |
| 故障注入成功 | [ ] | [ ] | [ ] |
| 预期行为发生 | [ ] | [ ] | [ ] |
| 服务恢复正常 | [ ] | [ ] | [ ] |
| 节点恢复成功 | [ ] | [ ] | [ ] |
| 记录演练结果 | [ ] | [ ] | [ ] |

---

## 快速参考命令

```bash
# 变量设置
SSH_OPTS="-i ~/.ssh/aeron-cluster -o StrictHostKeyChecking=no"
MAIN_IPS="172.31.16.122 172.31.20.42 172.31.16.136"
STANDBY_IPS="172.31.18.179 172.31.17.148 172.31.31.207"

# 查看所有节点状态
for IP in $MAIN_IPS $STANDBY_IPS; do
  echo "=== $IP ==="
  ssh $SSH_OPTS ec2-user@$IP "ps aux | grep '[C]luster' | awk '{print \$NF}'; tail -1 /opt/aeron/node.log" 2>&1
done

# 杀死指定节点
ssh $SSH_OPTS ec2-user@<IP> "kill -9 \$(pgrep -f 'ClusterNode\|StandbyNode')"

# 启动主集群节点
ssh $SSH_OPTS ec2-user@<IP> "nohup /opt/aeron/start-main-node.sh <NODE_ID> 172.31.16.122 172.31.20.42 172.31.16.136 > /opt/aeron/node.log 2>&1 &"

# 启动 Standby 节点
ssh $SSH_OPTS ec2-user@<IP> "nohup /opt/aeron/start-standby-node.sh <NODE_ID> 172.31.18.179 172.31.17.148 172.31.31.207 172.31.16.122 172.31.20.42 172.31.16.136 > /opt/aeron/node.log 2>&1 &"
```
