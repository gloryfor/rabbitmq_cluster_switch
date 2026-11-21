package com.example.rmqswitch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.RabbitConnectionDetails;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Periodically checks DNS A records for configured RabbitMQ hosts and logs an alert on DNS changes.
 */
@Service
public class DnsSwitchWatchdog {

    private static final Logger log = LoggerFactory.getLogger(DnsSwitchWatchdog.class);

    private final List<String> configuredHosts;
    private final boolean enabled;
    private final ConnectionStateTracker stateTracker;

    public DnsSwitchWatchdog(
            RabbitConnectionDetails connectionDetails,
            @Value("${app.dns-watchdog.enabled:true}") boolean enabled,
            ConnectionStateTracker stateTracker) {
        this.configuredHosts = connectionDetails.getAddresses().stream()
                .map(RabbitConnectionDetails.Address::host)
                .distinct()
                .toList();
        this.enabled = enabled;
        this.stateTracker = stateTracker;
    }

    @Scheduled(fixedDelayString = "${app.dns-watchdog.poll-interval-ms:2000}")
    public void checkDnsChange() {
        if (!enabled) {
            return;
        }

        try {
            List<String> resolvedIpList = resolveConfiguredIps();
            Set<String> resolvedIpSet = new HashSet<>(resolvedIpList);

            List<String> previousIps = stateTracker.getLastResolvedIps();
            stateTracker.updateResolvedIps(resolvedIpList);

            if (!previousIps.isEmpty() && !new HashSet<>(previousIps).equals(resolvedIpSet)) {
                String msg = String.format(
                        "DNS change detected for %s: %s -> %s. Please restart the application to switch cluster.",
                        configuredHosts, previousIps, resolvedIpList);
                log.warn("[DNS Watchdog] {}", msg);
                stateTracker.recordEvent(msg);
            }
        } catch (UnknownHostException ex) {
            log.error("[DNS Watchdog] Failed to resolve {}: {}", configuredHosts, ex.getMessage());
        }
    }

    public List<String> resolveConfiguredIps() throws UnknownHostException {
        List<String> resolvedIps = new ArrayList<>();
        UnknownHostException lastException = null;

        for (String host : configuredHosts) {
            try {
                for (InetAddress addr : InetAddress.getAllByName(host)) {
                    String ip = addr.getHostAddress();
                    if (!resolvedIps.contains(ip)) {
                        resolvedIps.add(ip);
                    }
                }
            } catch (UnknownHostException ex) {
                lastException = ex;
            }
        }

        if (resolvedIps.isEmpty() && lastException != null) {
            throw lastException;
        }
        return List.copyOf(resolvedIps);
    }

    public boolean isDnsMismatchDetected(List<String> currentDnsIps) {
        ConnectionStateTracker.ActiveConnectionInfo current = stateTracker.getCurrentConnection();
        if (!current.connected() || current.brokerIp() == null || currentDnsIps == null || currentDnsIps.isEmpty()) {
            return false;
        }
        return !currentDnsIps.contains(current.brokerIp());
    }

    public List<String> getConfiguredHosts() {
        return configuredHosts;
    }

    public boolean isWatchdogEnabled() {
        return enabled;
    }
}
