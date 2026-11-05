package com.bank.upgrade.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.oracle.OracleContainer;

/**
 * One throw-away Oracle Free container per test JVM, shared by every Spring context in it.
 * Oracle needs about 2 GB of RAM, so a container per context would exhaust Docker memory.
 */
@TestConfiguration(proxyBeanMethods = false)
public class OracleTestConfig {

    private static final OracleContainer ORACLE =
        new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
            .withUsername("test_owner")
            .withPassword("test_owner_pw")
            // A busy machine can need more than a minute to open the database
            .withStartupTimeoutSeconds(240);

    @Bean
    @ServiceConnection
    OracleContainer oracle() {
        return ORACLE;
    }
}
