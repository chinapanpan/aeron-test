#!/bin/bash
# Start an Aeron main cluster node
# Usage: ./start-main-node.sh <node-id> <host0> <host1> <host2>
#   e.g.: ./start-main-node.sh 0 172.31.16.122 172.31.20.42 172.31.16.136

set -e

NODE_ID=${1:?'Usage: start-main-node.sh <node-id> <host0> <host1> <host2>'}
HOST0=${2:?'Missing host0'}
HOST1=${3:?'Missing host1'}
HOST2=${4:?'Missing host2'}

HOSTNAMES="${HOST0},${HOST1},${HOST2}"
BASE_DIR="/opt/aeron/node${NODE_ID}"

echo "============================================"
echo "  Aeron Main Cluster - Node ${NODE_ID}"
echo "  Hostnames: ${HOSTNAMES}"
echo "  Base Dir: ${BASE_DIR}"
echo "============================================"

# Clean previous state
rm -rf ${BASE_DIR}
mkdir -p ${BASE_DIR}

JAR_PATH="/opt/aeron/aeron-cluster-demo-all.jar"

exec java \
  -server \
  -XX:+UnlockDiagnosticVMOptions \
  -XX:GuaranteedSafepointInterval=300000 \
  -XX:+UseParallelGC \
  -Xms512m -Xmx2g \
  --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  -Djava.net.preferIPv4Stack=true \
  -Dagrona.disable.bounds.checks=true \
  -Daeron.pre.touch.mapped.memory=true \
  -Daeron.socket.so_sndbuf=2m \
  -Daeron.socket.so_rcvbuf=2m \
  -Daeron.rcv.initial.window.length=2m \
  -Daeron.cluster.node.id=${NODE_ID} \
  -Daeron.cluster.hostnames=${HOSTNAMES} \
  -Daeron.cluster.dir=${BASE_DIR} \
  -cp ${JAR_PATH} \
  com.example.aeron.cluster.ClusterNode
