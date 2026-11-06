package com.bank.upgrade.upgrade.config;

import com.bank.upgrade.common.retention.RetentionRule;
import com.bank.upgrade.common.events.FileLoaded;
import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.messaging.EventSubscription;
import com.bank.upgrade.common.messaging.NonRetryableEventException;
import com.bank.upgrade.common.messaging.OutboxService;
import com.bank.upgrade.upgrade.domain.UpgradeRequestRepository;
import com.bank.upgrade.common.messaging.KafkaClientProperties;
import com.bank.upgrade.upgrade.gateway.AccountUpgradeGateway;
import com.bank.upgrade.upgrade.gateway.CoreCallLimiter;
import com.bank.upgrade.upgrade.gateway.RedisCoreCallLimiter;
import com.bank.upgrade.upgrade.run.RunRequestListener;
import com.bank.upgrade.upgrade.gateway.SimulatedAccountUpgradeGateway;
import com.bank.upgrade.upgrade.plan.EligibilityRules;
import com.bank.upgrade.upgrade.plan.UpgradePlanner;
import com.bank.upgrade.upgrade.run.UpgradeRunner;
import com.bank.upgrade.upgrade.run.UpgradeScheduler;
import com.bank.upgrade.upgrade.staging.IngestionStagingClient;
import com.bank.upgrade.upgrade.staging.StagingSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(UpgradeProperties.class)
public class UpgradeConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Calls the ingestion service with this service's client-credentials token (see ServiceClientAutoConfiguration). */
    @Bean
    StagingSource stagingSource(WebClient.Builder builder, ExchangeFilterFunction serviceTokenFilter, UpgradeProperties properties) {
        return new IngestionStagingClient(builder.baseUrl(properties.ingestionUrl()).filter(serviceTokenFilter).build());
    }

    @Bean
    EligibilityRules eligibilityRules(UpgradeProperties properties) {
        return new EligibilityRules(properties.rules());
    }

    @Bean
    UpgradePlanner upgradePlanner(StagingSource staging, EligibilityRules rules, UpgradeRequestRepository requests,
            OutboxService outbox, PlatformTransactionManager transactionManager) {
        return new UpgradePlanner(staging, rules, requests, outbox, transactionManager);
    }

    /** When a file has been loaded, queue one upgrade request per account. */
    @Bean
    EventSubscription fileLoadedSubscription(UpgradePlanner planner, ObjectMapper mapper) {
        return new EventSubscription(Topics.FILE_LOADED, event -> {
            FileLoaded file = event.dataAs(mapper, FileLoaded.class);
            if (file.fileId() <= 0 || file.businessDate() == null) {
                // Retrying cannot add the missing fields: park it on the dead-letter topic
                throw new NonRetryableEventException("FileLoaded event " + event.eventId() + " has no fileId or businessDate");
            }
            planner.plan(file, event.correlationId());
        });
    }

    /** {@code bank.upgrade.gateway=simulated} (the default) until a real core-system adapter is added under another value. */
    @Bean
    @ConditionalOnProperty(prefix = "bank.upgrade", name = "gateway", havingValue = "simulated", matchIfMissing = true)
    AccountUpgradeGateway accountUpgradeGateway(UpgradeProperties properties) {
        return new SimulatedAccountUpgradeGateway(properties.simulated().latency(), properties.simulated().failureRate());
    }

    /** Core-system calls per second for all copies together, counted in Redis; or no shared limit. */
    @Bean
    CoreCallLimiter coreCallLimiter(UpgradeProperties properties, ObjectProvider<ReactiveStringRedisTemplate> redis, Clock clock) {
        return properties.coreCallsPerSecond() > 0
            ? new RedisCoreCallLimiter(redis.getObject(), properties.coreCallsPerSecond(), clock)
            : CoreCallLimiter.none();
    }

    @Bean
    UpgradeRunner upgradeRunner(UpgradeRequestRepository requests, AccountUpgradeGateway gateway, CoreCallLimiter limiter,
            OutboxService outbox, UpgradeProperties properties, PlatformTransactionManager transactionManager,
            JdbcTemplate jdbc, Clock clock, MeterRegistry meters) {
        return new UpgradeRunner(requests, gateway, limiter, outbox, properties, transactionManager, jdbc, clock, meters);
    }

    /** Every copy hears "run now" (its own consumer group) and joins the run in the background. */
    @Bean
    RunRequestListener runRequestListener(KafkaClientProperties kafka, UpgradeProperties properties, ObjectMapper mapper,
            UpgradeRunner runner, @Value("${spring.application.name}") String service) {
        return new RunRequestListener(kafka.consumerConfig(), service + "-" + properties.instanceId(), mapper,
            runId -> Thread.ofVirtual().name("upgrade-run").start(() -> runner.runIfIdle(runId)));
    }

    /** Off in tests and in instances that should not run the nightly upgrade ({@code bank.upgrade.schedule.enabled=false}). */
    @Bean
    @ConditionalOnProperty(prefix = "bank.upgrade.schedule", name = "enabled", havingValue = "true", matchIfMissing = true)
    UpgradeScheduler upgradeScheduler(UpgradeRunner runner, @Value("${bank.time-zone}") String zone) {
        return new UpgradeScheduler(runner, java.time.ZoneId.of(zone));
    }

    /**
     * Finished requests (upgraded, rejected, failed) of business days older than 400 days. Requests still PENDING or
     * IN_PROGRESS are never deleted; the audit trail keeps the history of every upgrade.
     */
    @Bean
    RetentionRule upgradeRequestsRetention(JdbcTemplate jdbc, @Value("${bank.time-zone}") String zone) {
        return RetentionRule.olderThanDate("upgrade-requests", java.time.Duration.ofDays(400), jdbc, """
            DELETE FROM UPGRADE_REQUEST
            WHERE STATUS IN ('UPGRADED', 'REJECTED', 'FAILED') AND BUSINESS_DATE < ? AND ROWNUM <= ?""",
            java.time.ZoneId.of(zone));
    }
}
