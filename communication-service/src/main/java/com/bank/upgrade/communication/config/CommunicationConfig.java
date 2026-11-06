package com.bank.upgrade.communication.config;

import com.bank.upgrade.common.retention.PartitionDrop;
import com.bank.upgrade.common.retention.RetentionRule;
import io.micrometer.core.instrument.MeterRegistry;
import com.bank.upgrade.common.events.AccountUpgraded;
import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.messaging.EventSubscription;
import com.bank.upgrade.common.messaging.NonRetryableEventException;
import com.bank.upgrade.common.messaging.OutboxService;
import com.bank.upgrade.communication.domain.LetterTemplateRepository;
import com.bank.upgrade.communication.domain.WelcomeLetterRepository;
import com.bank.upgrade.communication.letter.LetterContent;
import com.bank.upgrade.communication.letter.LetterRenderer;
import com.bank.upgrade.communication.letter.LetterService;
import com.bank.upgrade.communication.mail.LetterSendScheduler;
import com.bank.upgrade.communication.mail.LetterSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(CommunicationProperties.class)
public class CommunicationConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    LetterRenderer letterRenderer() {
        return new LetterRenderer();
    }

    @Bean
    LetterService letterService(LetterTemplateRepository templates, WelcomeLetterRepository letters, LetterRenderer renderer) {
        return new LetterService(templates, letters, renderer);
    }

    @Bean
    LetterContent letterContent(LetterTemplateRepository templates, LetterRenderer renderer) {
        return new LetterContent(templates, renderer);
    }

    /** Every upgraded account gets a welcome letter. */
    @Bean
    EventSubscription accountUpgradedSubscription(LetterService letters, ObjectMapper mapper) {
        return new EventSubscription(Topics.ACCOUNT_UPGRADED, event -> {
            AccountUpgraded upgrade = event.dataAs(mapper, AccountUpgraded.class);
            if (upgrade.requestId() <= 0 || upgrade.email() == null || upgrade.toType() == null || upgrade.businessDate() == null) {
                throw new NonRetryableEventException("AccountUpgraded event " + event.eventId() + " is missing fields");
            }
            letters.onUpgraded(upgrade, event.correlationId());
        });
    }

    @Bean
    LetterSender letterSender(WelcomeLetterRepository letters, LetterContent content, JavaMailSender mail,
            OutboxService outbox, CommunicationProperties properties, PlatformTransactionManager transactionManager,
            Clock clock, MeterRegistry meters) {
        return new LetterSender(letters, content, mail, outbox, properties, transactionManager, clock, meters);
    }

    /** Off in tests that drive sending themselves ({@code bank.communication.schedule.enabled=false}). */
    @Bean
    @ConditionalOnProperty(prefix = "bank.communication.schedule", name = "enabled", havingValue = "true", matchIfMissing = true)
    LetterSendScheduler letterSendScheduler(LetterSender sender, CommunicationProperties properties) {
        return new LetterSendScheduler(sender, properties.batchSize());
    }

    /**
     * Letters that were sent or failed for good, 400 days after they were written. PENDING letters are never deleted.
     * The table is partitioned by month written (UTC): a month with no PENDING letter left is dropped whole.
     */
    @Bean
    RetentionRule lettersRetention(JdbcTemplate jdbc) {
        return RetentionRule.olderThanTimestamp("letters", java.time.Duration.ofDays(400), jdbc, """
            DELETE FROM WELCOME_LETTER
            WHERE STATUS IN ('SENT', 'FAILED') AND GENERATED_AT < ? AND ROWNUM <= ?""")
            .withPartitions(PartitionDrop.byMonth(jdbc, "WELCOME_LETTER", java.time.ZoneOffset.UTC, "STATUS = 'PENDING'"));
    }
}
