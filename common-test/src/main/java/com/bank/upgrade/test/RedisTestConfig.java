package com.bank.upgrade.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;

/** One throw-away Redis (gateway sessions, the upgrade rate limit), shared by every Spring context in the test JVM. */
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestConfig {

    public static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.2-alpine").withExposedPorts(6379);

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return REDIS;
    }
}
