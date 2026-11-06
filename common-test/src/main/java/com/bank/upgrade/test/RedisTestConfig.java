package com.bank.upgrade.test;

import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;

/**
 * One throw-away Redis (gateway sessions, the upgrade rate limit), shared by every Spring context in the test JVM.
 * Like {@link OracleTestConfig}, only the connection details are a bean, so closing one context never stops it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestConfig {

    public static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.2-alpine").withExposedPorts(6379);

    @Bean
    RedisConnectionDetails redisConnectionDetails() {
        REDIS.start();   // once per JVM; does nothing when already running
        return new RedisConnectionDetails() {
            @Override
            public Standalone getStandalone() {
                return Standalone.of(REDIS.getHost(), REDIS.getMappedPort(6379));
            }
        };
    }
}
