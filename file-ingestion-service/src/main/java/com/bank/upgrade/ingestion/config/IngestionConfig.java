package com.bank.upgrade.ingestion.config;

import com.bank.upgrade.common.messaging.OutboxService;
import com.bank.upgrade.ingestion.IngestionScheduler;
import com.bank.upgrade.ingestion.IngestionService;
import com.bank.upgrade.ingestion.api.StagingStream;
import com.bank.upgrade.ingestion.domain.AccountStagingRepository;
import com.bank.upgrade.ingestion.domain.FileIngestionLogRepository;
import com.bank.upgrade.ingestion.domain.IngestionErrorRepository;
import com.bank.upgrade.ingestion.sftp.FileSource;
import com.bank.upgrade.ingestion.sftp.SshjFileSource;
import java.time.Clock;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(IngestionProperties.class)
public class IngestionConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    FileSource fileSource(IngestionProperties properties) {
        return new SshjFileSource(properties.sftp());
    }

    @Bean
    IngestionService ingestionService(
            FileSource source,
            IngestionProperties properties,
            FileIngestionLogRepository logs,
            AccountStagingRepository staging,
            IngestionErrorRepository errors,
            JobLauncher jobLauncher,
            JobExplorer jobExplorer,
            JobRepository jobRepository,
            Job ingestAccountsJob,
            OutboxService outbox,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        return new IngestionService(
            source, properties, logs, staging, errors, jobLauncher, jobExplorer, jobRepository, ingestAccountsJob, outbox, transactionManager, clock);
    }

    @Bean
    StagingStream stagingStream(JdbcTemplate jdbc) {
        return new StagingStream(jdbc, 500);
    }

    /** Off in tests and in instances that should not run the nightly load ({@code bank.ingestion.schedule.enabled=false}). */
    @Bean
    @ConditionalOnProperty(prefix = "bank.ingestion.schedule", name = "enabled", havingValue = "true", matchIfMissing = true)
    IngestionScheduler ingestionScheduler(IngestionService service) {
        return new IngestionScheduler(service);
    }
}
