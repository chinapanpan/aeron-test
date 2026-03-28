#!/bin/bash
# Deploy Aeron cluster artifacts to all 6 EC2 nodes
# Usage: ./deploy-all.sh

set -e

# Main cluster IPs
MAIN_HOSTS=(172.31.16.122 172.31.20.42 172.31.16.136)
# Standby cluster IPs
STANDBY_HOSTS=(172.31.18.179 172.31.17.148 172.31.31.207)

ALL_HOSTS=("${MAIN_HOSTS[@]}" "${STANDBY_HOSTS[@]}")

JAR_DIR="/home/ec2-user/aeron-test/build/libs"
SCRIPT_DIR="/home/ec2-user/aeron-test/scripts"

SSH_OPTS="-i /home/ec2-user/.ssh/aeron-cluster -o StrictHostKeyChecking=no -o ConnectTimeout=10"

echo "============================================"
echo "  Deploying Aeron Cluster to all nodes"
echo "  Main: ${MAIN_HOSTS[*]}"
echo "  Standby: ${STANDBY_HOSTS[*]}"
echo "============================================"

for HOST in "${ALL_HOSTS[@]}"; do
    echo ""
    echo "--- Deploying to ${HOST} ---"

    # Create remote directory
    ssh ${SSH_OPTS} ec2-user@${HOST} "sudo mkdir -p /opt/aeron && sudo chown ec2-user:ec2-user /opt/aeron"

    # Copy JARs
    scp ${SSH_OPTS} ${JAR_DIR}/aeron-cluster-demo-1.0.0-all.jar ec2-user@${HOST}:/opt/aeron/aeron-cluster-demo-all.jar
    scp ${SSH_OPTS} ${JAR_DIR}/aeron-cluster-client-1.0.0-all.jar ec2-user@${HOST}:/opt/aeron/aeron-cluster-client-all.jar

    # Copy scripts
    scp ${SSH_OPTS} ${SCRIPT_DIR}/start-main-node.sh ec2-user@${HOST}:/opt/aeron/
    scp ${SSH_OPTS} ${SCRIPT_DIR}/start-standby-node.sh ec2-user@${HOST}:/opt/aeron/
    scp ${SSH_OPTS} ${SCRIPT_DIR}/run-client.sh ec2-user@${HOST}:/opt/aeron/

    # Make scripts executable
    ssh ${SSH_OPTS} ec2-user@${HOST} "chmod +x /opt/aeron/*.sh"

    # Verify Java
    ssh ${SSH_OPTS} ec2-user@${HOST} "java -version 2>&1 | head -1"

    echo "--- ${HOST} done ---"
done

echo ""
echo "============================================"
echo "  Deployment complete!"
echo ""
echo "  To start main cluster:"
echo "    Node 0: ssh ec2-user@${MAIN_HOSTS[0]} '/opt/aeron/start-main-node.sh 0 ${MAIN_HOSTS[0]} ${MAIN_HOSTS[1]} ${MAIN_HOSTS[2]}'"
echo "    Node 1: ssh ec2-user@${MAIN_HOSTS[1]} '/opt/aeron/start-main-node.sh 1 ${MAIN_HOSTS[0]} ${MAIN_HOSTS[1]} ${MAIN_HOSTS[2]}'"
echo "    Node 2: ssh ec2-user@${MAIN_HOSTS[2]} '/opt/aeron/start-main-node.sh 2 ${MAIN_HOSTS[0]} ${MAIN_HOSTS[1]} ${MAIN_HOSTS[2]}'"
echo ""
echo "  To start standby cluster:"
echo "    Node 0: ssh ec2-user@${STANDBY_HOSTS[0]} '/opt/aeron/start-standby-node.sh 0 ${STANDBY_HOSTS[0]} ${STANDBY_HOSTS[1]} ${STANDBY_HOSTS[2]} ${MAIN_HOSTS[0]} ${MAIN_HOSTS[1]} ${MAIN_HOSTS[2]}'"
echo "    Node 1: ssh ec2-user@${STANDBY_HOSTS[1]} '/opt/aeron/start-standby-node.sh 1 ${STANDBY_HOSTS[0]} ${STANDBY_HOSTS[1]} ${STANDBY_HOSTS[2]} ${MAIN_HOSTS[0]} ${MAIN_HOSTS[1]} ${MAIN_HOSTS[2]}'"
echo "    Node 2: ssh ec2-user@${STANDBY_HOSTS[2]} '/opt/aeron/start-standby-node.sh 2 ${STANDBY_HOSTS[0]} ${STANDBY_HOSTS[1]} ${STANDBY_HOSTS[2]} ${MAIN_HOSTS[0]} ${MAIN_HOSTS[1]} ${MAIN_HOSTS[2]}'"
echo "============================================"
