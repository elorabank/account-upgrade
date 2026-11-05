package com.bank.upgrade.upgrade.config;

import com.bank.upgrade.common.events.FileLoaded;
import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.messaging.EventSubscription;
import com.bank.upgrade.common.messaging.NonRetryableEventException;
import com.bank.upgrade.common.messaging.OutboxService;
import com.bank.upgrade.upgrade.domain.UpgradeRequestRepository;
import com.bank.upgrade.upgrade.gateway.AccountUpgradeGateway;
import com.bank.upgrade.upgrade.gateway.SimulatedAccountUpgradeGateway;
import com.bank.upgrade.upgrade.plan.EligibilityRules;
import com.bank.upgrade.upgrade.plan.UpgradePlanner;
import com.bank.upgrade.upgrade.run.UpgradeRunner;
import com.bank.upgrade.upgrade.run.UpgradeScheduler;
import com.bank.upgrade.upgrade.staging.IngestionStagingClient;
import com.bank.upgrade.upgrade.staging.StagingSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
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
    AccountUpgradeGateway accountUpgradeGateway() {
        return new SimulatedAccountUpgradeGateway();
    }

    @Bean
    UpgradeRunner upgradeRunner(UpgradeRequestRepository requests, AccountUpgradeGateway gateway, OutboxService outbox,
            UpgradeProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        return new UpgradeRunner(requests, gateway, outbox, properties, transactionManager, clock);
    }

    /** Off in tests and in instances that should not run the nightly upgrade ({@code bank.upgrade.schedule.enabled=false}). */
    @Bean
    @ConditionalOnProperty(prefix = "bank.upgrade.schedule", name = "enabled", havingValue = "true", matchIfMissing = true)
    UpgradeScheduler upgradeScheduler(UpgradeRunner runner) {
        return new UpgradeScheduler(runner);
    }
}
