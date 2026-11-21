package com.example.rmqswitch.service;

import com.example.rmqswitch.config.RabbitMqConfig;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Produces periodic test messages and consumes them while logging the active RabbitMQ cluster and broker node.
 */
@Service
public class MessageTrafficService {

    private static final Logger log = LoggerFactory.getLogger(MessageTrafficService.class);

    private final RabbitTemplate rabbitTemplate;
    private final ConnectionStateTracker stateTracker;
    private final AtomicLong sequence = new AtomicLong(0);

    public MessageTrafficService(RabbitTemplate rabbitTemplate, ConnectionStateTracker stateTracker) {
        this.rabbitTemplate = rabbitTemplate;
        this.stateTracker = stateTracker;
    }

    @Scheduled(fixedRate = 2000, initialDelay = 3000)
    public void sendPeriodicMessage() {
        sendMessage("auto");
    }

    public String sendMessage(String source) {
        long seq = sequence.incrementAndGet();
        String payload = String.format("msg#%d [%s] at %s", seq, source, Instant.now());

        try {
            rabbitTemplate.convertAndSend(RabbitMqConfig.EXCHANGE_NAME, RabbitMqConfig.ROUTING_KEY, payload);
            ConnectionStateTracker.ActiveConnectionInfo info = stateTracker.getCurrentConnection();
            stateTracker.recordPublished(info.clusterName(), info.brokerIp(), seq);
            log.info("[PRODUCER] Sent seq={} via cluster={}, broker={} | payload='{}'",
                    seq, info.clusterName(), info.brokerIp(), payload);
            return payload;
        } catch (AmqpException ex) {
            log.warn("[PRODUCER] Failed to send seq={}: {}", seq, ex.getMessage());
            return "FAILED: " + ex.getMessage();
        }
    }

    @RabbitListener(queues = RabbitMqConfig.QUEUE_NAME)
    public void receiveMessage(String payload, @Header(AmqpHeaders.CHANNEL) Channel channel) {
        com.rabbitmq.client.Connection conn = channel.getConnection();
        Map<String, Object> serverProps = conn.getServerProperties();
        String clusterName = Objects.toString(serverProps.get("cluster_name"), "unknown");
        String brokerIp = conn.getAddress() != null ? conn.getAddress().getHostAddress() : "unknown";

        stateTracker.recordConsumed(clusterName, brokerIp, payload);
        log.info("[CONSUMER] Received from cluster={}, broker={} | payload='{}'",
                clusterName, brokerIp, payload);
    }
}
