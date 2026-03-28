package com.example.aeron.cluster;

import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.IdleStrategy;

/**
 * A simple echo clustered service for learning and testing.
 * Echoes back messages with metadata (leader ID, node ID, timestamp, message count).
 * Fully deterministic - uses only cluster-provided timestamps.
 */
public class EchoClusteredService implements ClusteredService {

    private Cluster cluster;
    private long messageCount = 0;
    private final ExpandableArrayBuffer responseBuffer = new ExpandableArrayBuffer();

    @Override
    public void onStart(Cluster cluster, Image snapshotImage) {
        this.cluster = cluster;
        System.out.println("[Service] Node " + cluster.memberId() + " started, role=" + cluster.role());

        if (snapshotImage != null) {
            // Restore state from snapshot
            System.out.println("[Service] Loading snapshot...");
            snapshotImage.poll((buffer, offset, length, header) -> {
                messageCount = buffer.getLong(offset);
                System.out.println("[Service] Restored messageCount=" + messageCount);
            }, 1);
        }
    }

    @Override
    public void onSessionOpen(ClientSession session, long timestamp) {
        System.out.println("[Service] Client session opened: " + session.id()
            + " at timestamp=" + timestamp);
    }

    @Override
    public void onSessionClose(ClientSession session, long timestamp, CloseReason closeReason) {
        System.out.println("[Service] Client session closed: " + session.id()
            + " reason=" + closeReason);
    }

    @Override
    public void onSessionMessage(
        ClientSession session,
        long timestamp,
        DirectBuffer buffer,
        int offset,
        int length,
        Header header) {

        messageCount++;

        // Read the incoming message
        byte[] msgBytes = new byte[length];
        buffer.getBytes(offset, msgBytes);
        String message = new String(msgBytes);

        // Build response with cluster metadata
        String response = String.format(
            "ACK|node=%d|role=%s|ts=%d|count=%d|msg=%s",
            cluster.memberId(),
            cluster.role(),
            timestamp,
            messageCount,
            message
        );

        byte[] responseBytes = response.getBytes();
        responseBuffer.putBytes(0, responseBytes);

        // Send response back to client, handling backpressure
        if (session != null) {
            final IdleStrategy idleStrategy = cluster.idleStrategy();
            while (session.offer(responseBuffer, 0, responseBytes.length) < 0) {
                idleStrategy.idle();
            }
        }

        if (messageCount % 1000 == 0) {
            System.out.println("[Service] Processed " + messageCount + " messages");
        }
    }

    @Override
    public void onTimerEvent(long correlationId, long timestamp) {
        // Timer events can be used for periodic tasks
    }

    @Override
    public void onTakeSnapshot(ExclusivePublication snapshotPublication) {
        System.out.println("[Service] Taking snapshot, messageCount=" + messageCount);
        final MutableDirectBuffer buffer = new ExpandableArrayBuffer();
        buffer.putLong(0, messageCount);

        final IdleStrategy idleStrategy = cluster.idleStrategy();
        while (snapshotPublication.offer(buffer, 0, Long.BYTES) < 0) {
            idleStrategy.idle();
        }
    }

    @Override
    public void onRoleChange(Cluster.Role newRole) {
        System.out.println("[Service] Node " + cluster.memberId() + " role changed to " + newRole);
    }

    @Override
    public void onTerminate(Cluster cluster) {
        System.out.println("[Service] Node " + cluster.memberId() + " terminating, messageCount=" + messageCount);
    }
}
