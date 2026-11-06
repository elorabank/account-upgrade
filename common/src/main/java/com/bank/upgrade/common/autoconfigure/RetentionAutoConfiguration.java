package com.bank.upgrade.common.autoconfigure;

import com.bank.upgrade.common.retention.RetentionProperties;
import com.bank.upgrade.common.retention.RetentionRule;
import com.bank.upgrade.common.retention.RetentionRunner;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The daily clean-up ({@link RetentionRunner}) in every service with a database, running the {@link RetentionRule}
 * beans the service declares. Off with {@code bank.retention.enabled=false}.
 */
@AutoConfiguration(afterName = {
    "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration",
    "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
    "org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration"})
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnBean({JdbcTemplate.class, PlatformTransactionManager.class})
@ConditionalOnProperty(prefix = "bank.retention", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RetentionProperties.class)
@EnableScheduling
public class RetentionAutoConfiguration {

    @Bean
    RetentionRunner retentionRunner(ObjectProvider<RetentionRule> rules, RetentionProperties properties,
            PlatformTransactionManager transactionManager, ObjectProvider<Clock> clock, ObjectProvider<MeterRegistry> meters) {
        return new RetentionRunner(rules.orderedStream().toList(), properties, transactionManager,
            clock.getIfAvailable(Clock::systemUTC), meters.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * The consumers' de-duplication records: a redelivered event can only come within Kafka's retention (7 days),
     * so 30 days leaves room for a replay from a dead-letter topic.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "bank.processed-events", name = "enabled", havingValue = "true")
    static class ProcessedEvents {

        @Bean
        RetentionRule processedEventsRetention(JdbcTemplate jdbc) {
            return RetentionRule.olderThanTimestamp("processed-events", Duration.ofDays(30), jdbc,
                "DELETE FROM PROCESSED_EVENT WHERE PROCESSED_AT < ? AND ROWNUM <= ?");
        }
    }
}
