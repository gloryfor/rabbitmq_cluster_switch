package com.example.rmqswitch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.security.Security;

@SpringBootApplication
@EnableScheduling
public class RmqSwitchApplication {

    static {
        // Disable JVM DNS caching so CoreDNS A-record switches are reflected immediately.
        Security.setProperty("networkaddress.cache.ttl", "0");
        Security.setProperty("networkaddress.cache.negative.ttl", "0");
    }

    public static void main(String[] args) {
        SpringApplication.run(RmqSwitchApplication.class, args);
    }
}
