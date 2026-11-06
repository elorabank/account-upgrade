package com.bank.upgrade.test;

import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.oracle.OracleContainer;

/**
 * One throw-away Oracle Free container per test JVM, shared by every Spring context in it.
 * Oracle needs about 2 GB of RAM, so a container per context would exhaust Docker memory.
 * <p>
 * The container is deliberately not a bean, only its connection details are: Spring Boot stops container beans when
 * their context closes, and the first cached context to close would take the database away from the others, whose
 * pools then keep retrying during shutdown (Surefire kills the JVM after 30 s). Testcontainers removes it when the
 * JVM exits.
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
    JdbcConnectionDetails oracleConnectionDetails() {
        ORACLE.start();   // once per JVM; does nothing when already running
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return ORACLE.getUsername();
            }

            @Override
            public String getPassword() {
                return ORACLE.getPassword();
            }

            @Override
            public String getJdbcUrl() {
                return ORACLE.getJdbcUrl();
            }
        };
    }
}
