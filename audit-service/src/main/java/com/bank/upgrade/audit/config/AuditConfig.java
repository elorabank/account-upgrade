package com.bank.upgrade.audit.config;

import com.bank.upgrade.audit.domain.AuditEventRepository;
import com.bank.upgrade.audit.record.AuditRecorder;
import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.messaging.EventSubscription;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The audit trail listens to every topic that records a business step or a person's action. */
@Configuration(proxyBeanMethods = false)
public class AuditConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    AuditRecorder auditRecorder(AuditEventRepository events, Clock clock) {
        return new AuditRecorder(events, clock);
    }

    @Bean
    EventSubscription auditFileLoaded(AuditRecorder recorder) {
        return audit(Topics.FILE_LOADED, recorder);
    }

    @Bean
    EventSubscription auditAccountUpgraded(AuditRecorder recorder) {
        return audit(Topics.ACCOUNT_UPGRADED, recorder);
    }

    @Bean
    EventSubscription auditUpgradeRejected(AuditRecorder recorder) {
        return audit(Topics.UPGRADE_REJECTED, recorder);
    }

    @Bean
    EventSubscription auditUpgradeFailed(AuditRecorder recorder) {
        return audit(Topics.UPGRADE_FAILED, recorder);
    }

    @Bean
    EventSubscription auditNotificationSent(AuditRecorder recorder) {
        return audit(Topics.NOTIFICATION_SENT, recorder);
    }

    @Bean
    EventSubscription auditNotificationFailed(AuditRecorder recorder) {
        return audit(Topics.NOTIFICATION_FAILED, recorder);
    }

    @Bean
    EventSubscription auditUserAction(AuditRecorder recorder) {
        return audit(Topics.USER_ACTION, recorder);
    }

    private static EventSubscription audit(String topic, AuditRecorder recorder) {
        return new EventSubscription(topic, event -> recorder.record(topic, event));
    }
}
