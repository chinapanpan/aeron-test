package com.example.aeron.cluster;

import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.ConsensusModule;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.MinMulticastFlowControlSupplier;
import io.aeron.driver.ThreadingMode;
import org.agrona.concurrent.NoOpLock;
import org.agrona.concurrent.ShutdownSignalBarrier;

import java.io.File;

/**
 * Standby cluster node - forms its own independent Raft cluster.
 * Can be promoted to take over if the entire primary cluster fails.
 *
 * Required system properties:
 *   -Daeron.cluster.node.id=0
 *   -Daeron.cluster.hostnames=sip0,sip1,sip2
 *   -Daeron.cluster.dir=/opt/aeron/standby-node0
 */
public class StandbyNode {

    public static void main(String[] args) {
        final int nodeId = Integer.getInteger("aeron.cluster.node.id", 0);
        final String hostnames = System.getProperty("aeron.cluster.hostnames");
        final String baseDir = System.getProperty("aeron.cluster.dir", "/opt/aeron/standby-node" + nodeId);

        if (hostnames == null) {
            throw new IllegalArgumentException("Must set -Daeron.cluster.hostnames");
        }

        final String[] hosts = hostnames.split(",");
        final String hostname = hosts[nodeId];
        final String clusterMembers = ClusterNode.buildClusterMembers(hosts);

        System.out.println("Starting Aeron Standby Cluster Node " + nodeId);
        System.out.println("  Hostname: " + hostname);
        System.out.println("  Standby Cluster Members: " + clusterMembers);

        final String aeronDirName = baseDir + "/aeron";
        final File clusterBaseDir = new File(baseDir);

        final ShutdownSignalBarrier barrier = new ShutdownSignalBarrier();

        final MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
            .aeronDirectoryName(aeronDirName)
            .threadingMode(ThreadingMode.DEDICATED)
            .termBufferSparseFile(true)
            .multicastFlowControlSupplier(new MinMulticastFlowControlSupplier())
            .dirDeleteOnStart(true);

        final AeronArchive.Context replicationArchiveContext = new AeronArchive.Context()
            .controlResponseChannel("aeron:udp?endpoint=" + hostname + ":0");

        final Archive.Context archiveContext = new Archive.Context()
            .aeronDirectoryName(aeronDirName)
            .archiveDir(new File(clusterBaseDir, "archive"))
            .controlChannel(ClusterNode.udpChannel(nodeId, hostname, 1))
            .archiveClientContext(replicationArchiveContext)
            .localControlChannel("aeron:ipc?term-length=64k")
            .replicationChannel("aeron:udp?endpoint=" + hostname + ":0")
            .recordingEventsEnabled(false)
            .threadingMode(ArchiveThreadingMode.SHARED)
            .deleteArchiveOnStart(true);

        final AeronArchive.Context aeronArchiveContext = new AeronArchive.Context()
            .lock(NoOpLock.INSTANCE)
            .controlRequestChannel(archiveContext.localControlChannel())
            .controlRequestStreamId(archiveContext.localControlStreamId())
            .controlResponseChannel(archiveContext.localControlChannel())
            .aeronDirectoryName(aeronDirName);

        final ConsensusModule.Context consensusModuleContext = new ConsensusModule.Context()
            .clusterMemberId(nodeId)
            .clusterMembers(clusterMembers)
            .clusterDir(new File(clusterBaseDir, "cluster"))
            .archiveContext(aeronArchiveContext.clone())
            .serviceCount(1)
            .ingressChannel("aeron:udp")
            .replicationChannel("aeron:udp?endpoint=" + hostname + ":0")
            .deleteDirOnStart(true);

        final ClusteredServiceContainer.Context serviceContainerContext = new ClusteredServiceContainer.Context()
            .aeronDirectoryName(aeronDirName)
            .archiveContext(aeronArchiveContext.clone())
            .clusterDir(new File(clusterBaseDir, "cluster"))
            .clusteredService(new EchoClusteredService())
            .serviceId(0)
            .errorHandler(throwable -> {
                System.err.println("[Standby Error] " + throwable.getMessage());
                throwable.printStackTrace();
            });

        try (
            ClusteredMediaDriver clusteredMediaDriver = ClusteredMediaDriver.launch(
                mediaDriverContext, archiveContext, consensusModuleContext);
            ClusteredServiceContainer container = ClusteredServiceContainer.launch(serviceContainerContext)
        ) {
            System.out.println("[Standby Node " + nodeId + "] Started successfully");
            System.out.println("[Standby Node " + nodeId + "] Waiting for shutdown signal...");
            barrier.await();
            System.out.println("[Standby Node " + nodeId + "] Shutdown signal received");
        }
    }
}
