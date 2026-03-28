#!/bin/bash
# Run Aeron Cluster client
# Usage: ./run-client.sh <host0> <host1> <host2> [message-count] [message-text] [benchmark]
#   e.g.: ./run-client.sh 172.31.16.122 172.31.20.42 172.31.16.136 100 hello false
#   e.g.: ./run-client.sh 172.31.16.122 172.31.20.42 172.31.16.136 10000 bench true

set -e

HOST0=${1:?'Usage: run-client.sh <host0> <host1> <host2> [count] [msg] [benchmark]'}
HOST1=${2:?'Missing host1'}
HOST2=${3:?'Missing host2'}
MSG_COUNT=${4:-100}
MSG_TEXT=${5:-hello}
BENCHMARK=${6:-false}

INGRESS_ENDPOINTS="0=${HOST0}:9002,1=${HOST1}:9002,2=${HOST2}:9002"

echo "============================================"
echo "  Aeron Cluster Client"
echo "  Ingress: ${INGRESS_ENDPOINTS}"
echo "  Messages: ${MSG_COUNT}"
echo "  Benchmark: ${BENCHMARK}"
echo "============================================"

JAR_PATH="/opt/aeron/aeron-cluster-client-all.jar"

java \
  -Djava.net.preferIPv4Stack=true \
  -Daeron.cluster.ingress.endpoints=${INGRESS_ENDPOINTS} \
  -Daeron.cluster.message.count=${MSG_COUNT} \
  -Daeron.cluster.message.text=${MSG_TEXT} \
  -Daeron.cluster.benchmark=${BENCHMARK} \
  -cp ${JAR_PATH} \
  com.example.aeron.client.ClusterClient
