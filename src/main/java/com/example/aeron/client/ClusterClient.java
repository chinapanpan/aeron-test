package com.example.aeron.client;

import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;

/**
 * Client that connects to the Aeron Cluster and sends/receives messages.
 * Used for testing, benchmarking, and fault drill exercises.
 *
 * Required system properties:
 *   -Daeron.cluster.ingress.endpoints=ip0:9002,ip1:9002,ip2:9002
 *   -Daeron.cluster.message.count=100  (number of messages to send)
 *   -Daeron.cluster.message.text=hello  (message text)
 */
public class ClusterClient implements EgressListener {

    private long receivedCount = 0;
    private long lastResponseTimestamp = 0;
    private long totalLatencyNs = 0;

    @Override
    public void onMessage(
        long clusterSessionId,
        long timestamp,
        DirectBuffer buffer,
        int offset,
        int length,
        Header header) {

        byte[] bytes = new byte[length];
        buffer.getBytes(offset, bytes);
        String response = new String(bytes);

        receivedCount++;
        long latencyNs = System.nanoTime() - lastResponseTimestamp;
        totalLatencyNs += latencyNs;

        if (receivedCount <= 5 || receivedCount % 1000 == 0) {
            System.out.println("[Client] Response #" + receivedCount + ": " + response);
        }
    }

    public static void main(String[] args) throws InterruptedException {
        final String ingressEndpoints = System.getProperty(
            "aeron.cluster.ingress.endpoints", "localhost:9002");
        final String egressEndpoint = System.getProperty(
            "aeron.cluster.egress.endpoint", "localhost:0");
        final int messageCount = Integer.getInteger("aeron.cluster.message.count", 100);
        final String messageText = System.getProperty("aeron.cluster.message.text", "hello");
        final boolean benchmark = Boolean.getBoolean("aeron.cluster.benchmark");

        System.out.println("Aeron Cluster Client");
        System.out.println("  Ingress Endpoints: " + ingressEndpoints);
        System.out.println("  Message Count: " + messageCount);
        System.out.println("  Benchmark Mode: " + benchmark);

        final ClusterClient listener = new ClusterClient();
        final IdleStrategy idleStrategy = new BackoffIdleStrategy(10, 10, 1000, 1_000_000);

        final String aeronDir = "/tmp/aeron-client-" + System.currentTimeMillis();

        try (
            MediaDriver mediaDriver = MediaDriver.launch(new MediaDriver.Context()
                .aeronDirectoryName(aeronDir)
                .threadingMode(ThreadingMode.SHARED)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true));
            AeronCluster aeronCluster = AeronCluster.connect(new AeronCluster.Context()
                .aeronDirectoryName(aeronDir)
                .egressListener(listener)
                .egressChannel("aeron:udp?endpoint=" + egressEndpoint)
                .ingressChannel("aeron:udp")
                .ingressEndpoints(ingressEndpoints))
        ) {
            System.out.println("[Client] Connected to cluster");

            final ExpandableArrayBuffer msgBuffer = new ExpandableArrayBuffer();

            if (benchmark) {
                // Warmup
                System.out.println("[Client] Warming up (1000 messages)...");
                sendMessages(aeronCluster, msgBuffer, idleStrategy, listener, "warmup", 1000);

                // Reset counters
                listener.receivedCount = 0;
                listener.totalLatencyNs = 0;

                // Benchmark
                System.out.println("[Client] Starting benchmark (" + messageCount + " messages)...");
                long startNs = System.nanoTime();
                sendMessages(aeronCluster, msgBuffer, idleStrategy, listener, messageText, messageCount);
                long elapsedNs = System.nanoTime() - startNs;

                double elapsedMs = elapsedNs / 1_000_000.0;
                double throughput = messageCount / (elapsedMs / 1000.0);
                double avgLatencyUs = (listener.totalLatencyNs / (double) listener.receivedCount) / 1000.0;

                System.out.println("\n===== Benchmark Results =====");
                System.out.println("  Messages sent: " + messageCount);
                System.out.println("  Messages received: " + listener.receivedCount);
                System.out.println("  Total time: " + String.format("%.2f", elapsedMs) + " ms");
                System.out.println("  Throughput: " + String.format("%.0f", throughput) + " msg/sec");
                System.out.println("  Avg round-trip: " + String.format("%.2f", avgLatencyUs) + " us");
                System.out.println("=============================");
            } else {
                // Interactive mode
                sendMessages(aeronCluster, msgBuffer, idleStrategy, listener, messageText, messageCount);
                System.out.println("\n[Client] Sent " + messageCount + " messages, received " + listener.receivedCount);
            }
        }
    }

    private static void sendMessages(
        AeronCluster cluster,
        ExpandableArrayBuffer buffer,
        IdleStrategy idleStrategy,
        ClusterClient listener,
        String message,
        int count) {

        for (int i = 0; i < count; i++) {
            String msg = message + "-" + i;
            byte[] msgBytes = msg.getBytes();
            buffer.putBytes(0, msgBytes);

            listener.lastResponseTimestamp = System.nanoTime();

            while (cluster.offer(buffer, 0, msgBytes.length) < 0) {
                idleStrategy.idle();
                cluster.pollEgress();
            }

            // Poll for responses
            int pollCount = 0;
            while (pollCount < 10) {
                int fragments = cluster.pollEgress();
                if (fragments > 0) break;
                idleStrategy.idle();
                pollCount++;
            }
        }

        // Drain remaining responses
        long deadline = System.nanoTime() + 5_000_000_000L; // 5 second timeout
        while (listener.receivedCount < count && System.nanoTime() < deadline) {
            cluster.pollEgress();
            idleStrategy.idle();
        }
    }
}
