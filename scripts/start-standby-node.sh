#!/bin/bash
# Start an Aeron standby cluster node
# Usage: ./start-standby-node.sh <node-id> <shost0> <shost1> <shost2> <phost0> <phost1> <phost2>

set -e

NODE_ID=${1:?'Usage: start-standby-node.sh <node-id> <shost0> <shost1> <shost2> <phost0> <phost1> <phost2>'}
SHOST0=${2:?'Missing standby host0'}
SHOST1=${3:?'Missing standby host1'}
SHOST2=${4:?'Missing standby host2'}
PHOST0=${5:?'Missing primary host0'}
PHOST1=${6:?'Missing primary host1'}
PHOST2=${7:?'Missing primary host2'}

STANDBY_HOSTNAMES="${SHOST0},${SHOST1},${SHOST2}"
PRIMARY_HOSTNAMES="${PHOST0},${PHOST1},${PHOST2}"
BASE_DIR="/opt/aeron/standby-node${NODE_ID}"

echo "============================================"
echo "  Aeron Standby Cluster - Node ${NODE_ID}"
echo "  Standby Hostnames: ${STANDBY_HOSTNAMES}"
echo "  Primary Hostnames: ${PRIMARY_HOSTNAMES}"
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
  -Daeron.cluster.hostnames=${STANDBY_HOSTNAMES} \
  -Daeron.cluster.primary.hostnames=${PRIMARY_HOSTNAMES} \
  -Daeron.cluster.dir=${BASE_DIR} \
  -cp ${JAR_PATH} \
  com.example.aeron.cluster.StandbyNode
