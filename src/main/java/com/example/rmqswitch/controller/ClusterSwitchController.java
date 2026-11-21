package com.example.rmqswitch.controller;

import com.example.rmqswitch.service.ConnectionStateTracker;
import com.example.rmqswitch.service.DnsSwitchWatchdog;
import com.example.rmqswitch.service.MessageTrafficService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints to inspect cluster connection status and publish test messages.
 */
@RestController
@RequestMapping("/api")
public class ClusterSwitchController {

    private final String configuredAddresses;
    private final ConnectionStateTracker stateTracker;
    private final DnsSwitchWatchdog watchdog;
    private final MessageTrafficService messageTrafficService;

    public ClusterSwitchController(
            @Value("${spring.rabbitmq.addresses}") String configuredAddresses,
            ConnectionStateTracker stateTracker,
            DnsSwitchWatchdog watchdog,
            MessageTrafficService messageTrafficService) {
        this.configuredAddresses = configuredAddresses;
        this.stateTracker = stateTracker;
        this.watchdog = watchdog;
        this.messageTrafficService = messageTrafficService;
    }

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        List<String> liveDnsIps;
        boolean resolutionFailed = false;
        try {
            liveDnsIps = watchdog.resolveConfiguredIps();
            stateTracker.updateResolvedIps(liveDnsIps);
        } catch (UnknownHostException ex) {
            resolutionFailed = true;
            liveDnsIps = List.of("RESOLUTION_ERROR: " + ex.getMessage());
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("configuredAddresses", configuredAddresses);
        response.put("dnsHosts", watchdog.getConfiguredHosts());
        response.put("liveDnsIps", liveDnsIps);
        response.put("activeConnection", stateTracker.getCurrentConnection());
        response.put("watchdog", Map.of(
                "enabled", watchdog.isWatchdogEnabled(),
                "dnsMismatchDetected", !resolutionFailed && watchdog.isDnsMismatchDetected(liveDnsIps)
        ));
        response.put("publishedByCluster", stateTracker.getPublishedStats());
        response.put("consumedByCluster", stateTracker.getConsumedStats());
        response.put("switchHistory", stateTracker.getSwitchHistory());
        response.put("recentMessages", stateTracker.getRecentMessages());
        return response;
    }

    @PostMapping("/messages")
    public Map<String, Object> sendManualMessage() {
        String result = messageTrafficService.sendMessage("manual-api");
        return Map.of(
                "result", result,
                "activeConnection", stateTracker.getCurrentConnection()
        );
    }
}
