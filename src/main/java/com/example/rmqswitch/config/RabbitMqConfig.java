package com.example.rmqswitch.config;

import com.example.rmqswitch.service.ConnectionStateTracker;
import com.rabbitmq.client.ShutdownSignalException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionListener;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Objects;

/**
 * Configures Spring Boot auto-configured RabbitMQ connection tracking and quorum queue topology.
 */
@Configuration
public class RabbitMqConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqConfig.class);

    public static final String EXCHANGE_NAME = "switch.test.exchange";
    public static final String QUEUE_NAME = "switch.test.queue";
    public static final String ROUTING_KEY = "switch.test.routing.key";

    @Bean
    public SmartInitializingSingleton registerConnectionListener(
            CachingConnectionFactory connectionFactory,
            ConnectionStateTracker stateTracker) {
        return () -> connectionFactory.addConnectionListener(new ConnectionListener() {
            @Override
            public void onCreate(Connection connection) {
                com.rabbitmq.client.Connection delegate = connection.getDelegate();
                Map<String, Object> serverProps = delegate.getServerProperties();
                String clusterName = Objects.toString(serverProps.get("cluster_name"), "unknown");
                String version = Objects.toString(serverProps.get("version"), "unknown");
                String brokerIp = delegate.getAddress() != null
                        ? delegate.getAddress().getHostAddress()
                        : "unknown";
                int brokerPort = delegate.getPort();

                stateTracker.recordConnectionCreated(clusterName, brokerIp, brokerPort, version);
                log.info("==================================================================");
                log.info("[AMQP CONNECTED] cluster={}, broker={}:{}, version={}",
                        clusterName, brokerIp, brokerPort, version);
                log.info("==================================================================");
            }

            @Override
            public void onShutDown(ShutdownSignalException signal) {
                String reason = signal.getReason() != null
                        ? signal.getReason().toString()
                        : signal.getMessage();
                stateTracker.recordConnectionShutdown(reason);
                log.warn("[AMQP SHUTDOWN] Connection closed: {}", reason);
            }
        });
    }

    @Bean
    public DirectExchange switchTestExchange() {
        return new DirectExchange(EXCHANGE_NAME, true, false);
    }

    @Bean
    public Queue switchTestQueue() {
        return QueueBuilder.durable(QUEUE_NAME)
                .quorum()
                .build();
    }

    @Bean
    public Binding switchTestBinding(Queue switchTestQueue, DirectExchange switchTestExchange) {
        return BindingBuilder.bind(switchTestQueue).to(switchTestExchange).with(ROUTING_KEY);
    }
}
