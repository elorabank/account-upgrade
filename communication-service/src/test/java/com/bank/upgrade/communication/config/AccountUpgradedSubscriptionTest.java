package com.bank.upgrade.communication.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.bank.upgrade.common.events.AccountUpgraded;
import com.bank.upgrade.common.events.EventEnvelope;
import com.bank.upgrade.common.messaging.EventSubscription;
import com.bank.upgrade.common.messaging.NonRetryableEventException;
import com.bank.upgrade.communication.letter.LetterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The account.upgraded handler: a complete event queues a letter, an incomplete one is parked at once. */
class AccountUpgradedSubscriptionTest {

    final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    final LetterService letters = mock(LetterService.class);
    final EventSubscription subscription = new CommunicationConfig().accountUpgradedSubscription(letters, mapper);

    private EventEnvelope event(AccountUpgraded upgrade) {
        return new EventEnvelope(1, "e1", "AccountUpgraded", "file-3", Instant.now(), "system:upgrade", mapper.valueToTree(upgrade));
    }

    @Test
    void anUpgradeQueuesItsLetterUnderTheEventsCorrelationId() throws Exception {
        var upgrade = new AccountUpgraded(7, "AC1", "C1", "Asha", "a@example.com", "SAVINGS", "SAVINGS_PLUS",
            LocalDate.of(2026, 11, 10), Instant.parse("2026-11-10T21:30:00Z"));

        subscription.handler().handle(event(upgrade));

        verify(letters).onUpgraded(eq(upgrade), eq("file-3"));
    }

    @Test
    void anUpgradeWithoutAnEmailAddressCannotSucceedOnRetry() {
        var noEmail = new AccountUpgraded(7, "AC1", "C1", "Asha", null, "SAVINGS", "SAVINGS_PLUS", LocalDate.of(2026, 11, 10), Instant.now());

        assertThatThrownBy(() -> subscription.handler().handle(event(noEmail))).isInstanceOf(NonRetryableEventException.class);
        verify(letters, never()).onUpgraded(any(), any());
    }
}
