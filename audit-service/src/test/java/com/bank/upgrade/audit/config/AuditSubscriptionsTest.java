package com.bank.upgrade.audit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bank.upgrade.audit.domain.AuditEvent;
import com.bank.upgrade.audit.domain.AuditEventRepository;
import com.bank.upgrade.common.events.EventEnvelope;
import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.messaging.EventSubscription;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The audit service listens to every business topic and records each event once, with the database mocked. */
class AuditSubscriptionsTest {

    final AuditEventRepository events = mock(AuditEventRepository.class);
    final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(AuditConfig.class)
        .withBean(AuditEventRepository.class, () -> events);

    @Test
    void everyBusinessTopicIsAudited() {
        context.run(ctx -> assertThat(ctx.getBeansOfType(EventSubscription.class).values())
            .extracting(EventSubscription::topic)
            .containsExactlyInAnyOrder(Topics.FILE_LOADED, Topics.ACCOUNT_UPGRADED, Topics.UPGRADE_REJECTED,
                Topics.UPGRADE_FAILED, Topics.NOTIFICATION_SENT, Topics.NOTIFICATION_FAILED, Topics.USER_ACTION));
    }

    @Test
    void anEventIsRecordedWithItsTopicActorAndCorrelationIdAndOnlyOnce() {
        var mapper = new ObjectMapper();
        var event = new EventEnvelope(1, "e1", "AccountUpgraded", "file-3", Instant.parse("2026-11-10T21:30:00Z"),
            "system:account-upgrade-service", mapper.createObjectNode().put("accountNo", "AC1"));

        context.run(ctx -> {
            var upgraded = ctx.getBeansOfType(EventSubscription.class).values().stream()
                .filter(s -> s.topic().equals(Topics.ACCOUNT_UPGRADED)).findFirst().orElseThrow();

            upgraded.handler().handle(event);
            ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
            verify(events).save(saved.capture());
            assertThat(saved.getValue().getTopic()).isEqualTo(Topics.ACCOUNT_UPGRADED);
            assertThat(saved.getValue().getAccountNo()).isEqualTo("AC1");
            assertThat(saved.getValue().getActor()).isEqualTo("system:account-upgrade-service");
            assertThat(saved.getValue().getCorrelationId()).isEqualTo("file-3");

            // The same event again (a replay): already there, nothing new
            org.mockito.Mockito.clearInvocations(events);
            when(events.existsByEventId("e1")).thenReturn(true);
            upgraded.handler().handle(event);
            verify(events, never()).save(any());
        });
    }
}
