package com.example.rmqswitch.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe tracker for current RabbitMQ cluster connection, DNS resolution, and message metrics.
 */
@Service
public class ConnectionStateTracker {

    private static final int MAX_HISTORY = 50;
    private static final int MAX_MESSAGES = 20;

    public record ActiveConnectionInfo(
            boolean connected,
            String clusterName,
            String brokerIp,
            int brokerPort,
            String brokerVersion,
            Instant connectedAt,
            String lastShutdownReason
    ) {
        public static ActiveConnectionInfo disconnected(String reason) {
            return new ActiveConnectionInfo(false, null, null, 0, null, null, reason);
        }
    }

    private final AtomicReference<ActiveConnectionInfo> currentConnection =
            new AtomicReference<>(ActiveConnectionInfo.disconnected("Initializing"));
    private final AtomicReference<List<String>> lastResolvedIps =
            new AtomicReference<>(Collections.emptyList());

    private final ConcurrentHashMap<String, AtomicLong> publishedByCluster = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> consumedByCluster = new ConcurrentHashMap<>();
    private final Deque<String> switchHistory = new ConcurrentLinkedDeque<>();
    private final Deque<String> recentMessages = new ConcurrentLinkedDeque<>();

    public void updateResolvedIps(List<String> ips) {
        lastResolvedIps.set(List.copyOf(ips));
    }

    public List<String> getLastResolvedIps() {
        return lastResolvedIps.get();
    }

    public ActiveConnectionInfo getCurrentConnection() {
        return currentConnection.get();
    }

    public void recordConnectionCreated(String clusterName, String brokerIp, int brokerPort, String brokerVersion) {
        ActiveConnectionInfo prev = currentConnection.get();
        ActiveConnectionInfo next = new ActiveConnectionInfo(
                true, clusterName, brokerIp, brokerPort, brokerVersion, Instant.now(), null
        );
        currentConnection.set(next);

        if (prev.clusterName() != null && !prev.clusterName().equals(clusterName)) {
            recordEvent(String.format("Cluster switched: %s (%s) -> %s (%s)",
                    prev.clusterName(), prev.brokerIp(), clusterName, brokerIp));
        } else {
            recordEvent(String.format("Connected to cluster=%s, broker=%s:%d (v%s)",
                    clusterName, brokerIp, brokerPort, brokerVersion));
        }
    }

    public void recordConnectionShutdown(String reason) {
        ActiveConnectionInfo prev = currentConnection.get();
        currentConnection.set(ActiveConnectionInfo.disconnected(reason));
        recordEvent(String.format("Connection shutdown (was cluster=%s, broker=%s): %s",
                prev.clusterName(), prev.brokerIp(), reason));
    }

    public void recordPublished(String clusterName, String brokerIp, long seq) {
        String key = clusterName != null ? clusterName : "unknown";
        publishedByCluster.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    public void recordConsumed(String clusterName, String brokerIp, String payload) {
        String key = clusterName != null ? clusterName : "unknown";
        consumedByCluster.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();

        String entry = String.format("[%s] cluster=%s broker=%s | %s",
                Instant.now(), key, brokerIp, payload);
        recentMessages.addFirst(entry);
        while (recentMessages.size() > MAX_MESSAGES) {
            recentMessages.pollLast();
        }
    }

    public void recordEvent(String event) {
        String entry = String.format("[%s] %s", Instant.now(), event);
        switchHistory.addFirst(entry);
        while (switchHistory.size() > MAX_HISTORY) {
            switchHistory.pollLast();
        }
    }

    public Map<String, Long> getPublishedStats() {
        return toSnapshotMap(publishedByCluster);
    }

    public Map<String, Long> getConsumedStats() {
        return toSnapshotMap(consumedByCluster);
    }

    public List<String> getSwitchHistory() {
        return new ArrayList<>(switchHistory);
    }

    public List<String> getRecentMessages() {
        return new ArrayList<>(recentMessages);
    }

    private Map<String, Long> toSnapshotMap(ConcurrentHashMap<String, AtomicLong> source) {
        Map<String, Long> result = new LinkedHashMap<>();
        source.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }
}
