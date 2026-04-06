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

import java.util.Arrays;

/**
 * Aeron Cluster benchmark client with detailed latency statistics.
 *
 * System properties:
 *   -Daeron.cluster.ingress.endpoints=0=ip0:9002,1=ip1:9102,2=ip2:9202
 *   -Daeron.cluster.egress.endpoint=myip:0
 *   -Daeron.cluster.message.count=10000
 *   -Daeron.cluster.benchmark=true
 *   -Daeron.cluster.warmup.count=2000
 *   -Daeron.cluster.rounds=3
 */
public class ClusterClient implements EgressListener {

    private long receivedCount = 0;
    private long sendTimestampNs = 0;
    private long[] latencies;
    private int latencyIdx = 0;

    public ClusterClient(int maxMessages) {
        this.latencies = new long[maxMessages];
    }

    @Override
    public void onMessage(
        long clusterSessionId,
        long timestamp,
        DirectBuffer buffer,
        int offset,
        int length,
        Header header) {

        long nowNs = System.nanoTime();
        receivedCount++;

        if (latencyIdx < latencies.length) {
            latencies[latencyIdx++] = nowNs - sendTimestampNs;
        }

        if (!benchmarkMode && (receivedCount <= 5 || receivedCount % 1000 == 0)) {
            byte[] bytes = new byte[length];
            buffer.getBytes(offset, bytes);
            System.out.println("[Client] Response #" + receivedCount + ": " + new String(bytes));
        }
    }

    private static boolean benchmarkMode = false;

    public void resetStats(int maxMessages) {
        receivedCount = 0;
        latencyIdx = 0;
        if (latencies.length < maxMessages) {
            latencies = new long[maxMessages];
        }
    }

    public static void main(String[] args) throws InterruptedException {
        final String ingressEndpoints = System.getProperty(
            "aeron.cluster.ingress.endpoints", "localhost:9002");
        final String egressEndpoint = System.getProperty(
            "aeron.cluster.egress.endpoint", "localhost:0");
        final int messageCount = Integer.getInteger("aeron.cluster.message.count", 10000);
        final String messageText = System.getProperty("aeron.cluster.message.text", "hello");
        benchmarkMode = Boolean.getBoolean("aeron.cluster.benchmark");
        final int warmupCount = Integer.getInteger("aeron.cluster.warmup.count", 2000);
        final int rounds = Integer.getInteger("aeron.cluster.rounds", 3);

        System.out.println("Aeron Cluster Client");
        System.out.println("  Ingress Endpoints: " + ingressEndpoints);
        System.out.println("  Message Count: " + messageCount);
        System.out.println("  Benchmark Mode: " + benchmarkMode);
        if (benchmarkMode) {
            System.out.println("  Warmup Count: " + warmupCount);
            System.out.println("  Rounds: " + rounds);
        }

        final ClusterClient listener = new ClusterClient(Math.max(messageCount, warmupCount));
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

            if (benchmarkMode) {
                // Warmup phase
                System.out.println("\n[Warmup] Sending " + warmupCount + " messages...");
                listener.resetStats(warmupCount);
                sendMessages(aeronCluster, msgBuffer, idleStrategy, listener, "warmup", warmupCount);
                System.out.println("[Warmup] Done. Sent=" + warmupCount + " Received=" + listener.receivedCount);
                Thread.sleep(500);

                // Benchmark rounds
                long[] roundTps = new long[rounds];
                double[] roundAvgUs = new double[rounds];
                double[] roundP50Us = new double[rounds];
                double[] roundP99Us = new double[rounds];
                double[] roundP999Us = new double[rounds];
                double[] roundMinUs = new double[rounds];
                double[] roundMaxUs = new double[rounds];

                for (int r = 0; r < rounds; r++) {
                    listener.resetStats(messageCount);

                    System.out.println("\n[Round " + (r + 1) + "/" + rounds + "] Sending " + messageCount + " messages...");
                    long startNs = System.nanoTime();
                    sendMessages(aeronCluster, msgBuffer, idleStrategy, listener, messageText, messageCount);
                    long elapsedNs = System.nanoTime() - startNs;

                    double elapsedMs = elapsedNs / 1_000_000.0;
                    long tps = (long)(listener.receivedCount / (elapsedMs / 1000.0));

                    // Calculate percentiles
                    int count = listener.latencyIdx;
                    long[] sorted = Arrays.copyOf(listener.latencies, count);
                    Arrays.sort(sorted);

                    double avgUs = 0;
                    for (int i = 0; i < count; i++) avgUs += sorted[i];
                    avgUs = (avgUs / count) / 1000.0;

                    double p50Us = sorted[(int)(count * 0.50)] / 1000.0;
                    double p99Us = sorted[(int)(count * 0.99)] / 1000.0;
                    double p999Us = sorted[Math.min((int)(count * 0.999), count - 1)] / 1000.0;
                    double minUs = sorted[0] / 1000.0;
                    double maxUs = sorted[count - 1] / 1000.0;

                    roundTps[r] = tps;
                    roundAvgUs[r] = avgUs;
                    roundP50Us[r] = p50Us;
                    roundP99Us[r] = p99Us;
                    roundP999Us[r] = p999Us;
                    roundMinUs[r] = minUs;
                    roundMaxUs[r] = maxUs;

                    System.out.printf("  Sent: %d | Received: %d | Time: %.1f ms%n",
                        messageCount, listener.receivedCount, elapsedMs);
                    System.out.printf("  TPS: %d msg/sec%n", tps);
                    System.out.printf("  Latency (us): avg=%.1f  p50=%.1f  p99=%.1f  p999=%.1f  min=%.1f  max=%.1f%n",
                        avgUs, p50Us, p99Us, p999Us, minUs, maxUs);

                    Thread.sleep(500);
                }

                // Summary across all rounds
                System.out.println("\n============================================");
                System.out.println("  BENCHMARK SUMMARY (" + rounds + " rounds x " + messageCount + " msgs)");
                System.out.println("============================================");

                long avgTps = 0;
                double sumAvg = 0, sumP50 = 0, sumP99 = 0, sumP999 = 0;
                for (int r = 0; r < rounds; r++) {
                    avgTps += roundTps[r];
                    sumAvg += roundAvgUs[r];
                    sumP50 += roundP50Us[r];
                    sumP99 += roundP99Us[r];
                    sumP999 += roundP999Us[r];
                }
                avgTps /= rounds;

                System.out.printf("  Avg TPS:       %d msg/sec%n", avgTps);
                System.out.printf("  Avg Latency:   %.1f us%n", sumAvg / rounds);
                System.out.printf("  Avg P50:       %.1f us%n", sumP50 / rounds);
                System.out.printf("  Avg P99:       %.1f us%n", sumP99 / rounds);
                System.out.printf("  Avg P999:      %.1f us%n", sumP999 / rounds);
                System.out.println("============================================");

            } else {
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

            listener.sendTimestampNs = System.nanoTime();

            while (cluster.offer(buffer, 0, msgBytes.length) < 0) {
                idleStrategy.idle();
                cluster.pollEgress();
            }

            // Poll for response
            int pollCount = 0;
            while (pollCount < 10) {
                int fragments = cluster.pollEgress();
                if (fragments > 0) break;
                idleStrategy.idle();
                pollCount++;
            }
        }

        // Drain remaining responses
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (listener.receivedCount < count && System.nanoTime() < deadline) {
            cluster.pollEgress();
            idleStrategy.idle();
        }
    }
}
