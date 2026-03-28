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
 * Main entry point for an Aeron Cluster node.
 * Follows the official Aeron samples ClusterConfig pattern.
 *
 * Required system properties:
 *   -Daeron.cluster.node.id=0
 *   -Daeron.cluster.hostnames=ip0,ip1,ip2
 *   -Daeron.cluster.dir=/opt/aeron/node0
 */
public class ClusterNode {

    private static final int ARCHIVE_CONTROL_PORT_OFFSET = 1;
    private static final int CLIENT_FACING_PORT_OFFSET = 2;
    private static final int MEMBER_FACING_PORT_OFFSET = 3;
    private static final int LOG_PORT_OFFSET = 4;
    private static final int TRANSFER_PORT_OFFSET = 5;
    private static final int BASE_PORT = 9000;
    private static final int PORTS_PER_NODE = 100;

    public static void main(String[] args) {
        final int nodeId = Integer.getInteger("aeron.cluster.node.id", 0);
        final String hostnames = System.getProperty("aeron.cluster.hostnames", "localhost,localhost,localhost");
        final String baseDir = System.getProperty("aeron.cluster.dir", "/opt/aeron/node" + nodeId);

        final String[] hosts = hostnames.split(",");
        if (hosts.length != 3) {
            throw new IllegalArgumentException("Expected 3 hostnames, got " + hosts.length);
        }

        final String hostname = hosts[nodeId];
        final String clusterMembers = buildClusterMembers(hosts);

        System.out.println("Starting Aeron Cluster Node " + nodeId);
        System.out.println("  Hostname: " + hostname);
        System.out.println("  Base Dir: " + baseDir);
        System.out.println("  Cluster Members: " + clusterMembers);

        final String aeronDirName = baseDir + "/aeron";
        final File clusterBaseDir = new File(baseDir);

        final ShutdownSignalBarrier barrier = new ShutdownSignalBarrier();

        // Media Driver - following official sample pattern
        final MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
            .aeronDirectoryName(aeronDirName)
            .threadingMode(ThreadingMode.DEDICATED)
            .termBufferSparseFile(true)
            .multicastFlowControlSupplier(new MinMulticastFlowControlSupplier())
            .dirDeleteOnStart(true);

        // Archive replication context (for AeronArchive client used by replication)
        final AeronArchive.Context replicationArchiveContext = new AeronArchive.Context()
            .controlResponseChannel("aeron:udp?endpoint=" + hostname + ":0");

        // Archive
        final Archive.Context archiveContext = new Archive.Context()
            .aeronDirectoryName(aeronDirName)
            .archiveDir(new File(clusterBaseDir, "archive"))
            .controlChannel(udpChannel(nodeId, hostname, ARCHIVE_CONTROL_PORT_OFFSET))
            .archiveClientContext(replicationArchiveContext)
            .localControlChannel("aeron:ipc?term-length=64k")
            .replicationChannel("aeron:udp?endpoint=" + hostname + ":0")
            .recordingEventsEnabled(false)
            .threadingMode(ArchiveThreadingMode.SHARED)
            .deleteArchiveOnStart(true);

        // AeronArchive client context (used by ConsensusModule and ServiceContainer)
        final AeronArchive.Context aeronArchiveContext = new AeronArchive.Context()
            .lock(NoOpLock.INSTANCE)
            .controlRequestChannel(archiveContext.localControlChannel())
            .controlRequestStreamId(archiveContext.localControlStreamId())
            .controlResponseChannel(archiveContext.localControlChannel())
            .aeronDirectoryName(aeronDirName);

        // Consensus Module
        final ConsensusModule.Context consensusModuleContext = new ConsensusModule.Context()
            .clusterMemberId(nodeId)
            .clusterMembers(clusterMembers)
            .clusterDir(new File(clusterBaseDir, "cluster"))
            .archiveContext(aeronArchiveContext.clone())
            .serviceCount(1)
            .ingressChannel("aeron:udp")
            .replicationChannel("aeron:udp?endpoint=" + hostname + ":0")
            .deleteDirOnStart(true);

        // Clustered Service Container
        final ClusteredServiceContainer.Context serviceContainerContext = new ClusteredServiceContainer.Context()
            .aeronDirectoryName(aeronDirName)
            .archiveContext(aeronArchiveContext.clone())
            .clusterDir(new File(clusterBaseDir, "cluster"))
            .clusteredService(new EchoClusteredService())
            .serviceId(0)
            .errorHandler(throwable -> {
                System.err.println("[Error] " + throwable.getMessage());
                throwable.printStackTrace();
            });

        try (
            ClusteredMediaDriver clusteredMediaDriver = ClusteredMediaDriver.launch(
                mediaDriverContext, archiveContext, consensusModuleContext);
            ClusteredServiceContainer container = ClusteredServiceContainer.launch(serviceContainerContext)
        ) {
            System.out.println("[Node " + nodeId + "] Cluster node started successfully");
            System.out.println("[Node " + nodeId + "] Waiting for shutdown signal (SIGINT/SIGTERM)...");
            barrier.await();
            System.out.println("[Node " + nodeId + "] Shutdown signal received");
        }
    }

    static String buildClusterMembers(String[] hosts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hosts.length; i++) {
            sb.append(i);
            sb.append(',').append(endpoint(i, hosts[i], CLIENT_FACING_PORT_OFFSET));
            sb.append(',').append(endpoint(i, hosts[i], MEMBER_FACING_PORT_OFFSET));
            sb.append(',').append(endpoint(i, hosts[i], LOG_PORT_OFFSET));
            sb.append(',').append(endpoint(i, hosts[i], TRANSFER_PORT_OFFSET));
            sb.append(',').append(endpoint(i, hosts[i], ARCHIVE_CONTROL_PORT_OFFSET));
            sb.append('|');
        }
        return sb.toString();
    }

    static String endpoint(int nodeId, String host, int portOffset) {
        return host + ":" + calculatePort(nodeId, portOffset);
    }

    static int calculatePort(int nodeId, int portOffset) {
        return BASE_PORT + (nodeId * PORTS_PER_NODE) + portOffset;
    }

    static String udpChannel(int nodeId, String host, int portOffset) {
        return "aeron:udp?endpoint=" + host + ":" + calculatePort(nodeId, portOffset);
    }
}
