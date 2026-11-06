package com.bank.upgrade.upgrade.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.bank.upgrade.common.events.EventEnvelope;
import com.bank.upgrade.common.events.FileLoaded;
import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.messaging.EventSubscription;
import com.bank.upgrade.common.messaging.NonRetryableEventException;
import com.bank.upgrade.upgrade.plan.UpgradePlanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The account.file-loaded handler: a usable event is planned, an incomplete one is parked at once. */
class FileLoadedSubscriptionTest {

    final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    final UpgradePlanner planner = mock(UpgradePlanner.class);
    final EventSubscription subscription = new UpgradeConfig().fileLoadedSubscription(planner, mapper);

    private EventEnvelope event(FileLoaded file) {
        return new EventEnvelope(1, "evt-1", "FileLoaded", "file-3", Instant.now(), "system:ingestion", mapper.valueToTree(file));
    }

    @Test
    void aLoadedFileIsPlannedUnderItsCorrelationId() throws Exception {
        var file = new FileLoaded(3, "accounts_20270201.xlsx", LocalDate.of(2027, 2, 1), 10, 9, 1);

        subscription.handler().handle(event(file));

        assertThat(subscription.topic()).isEqualTo(Topics.FILE_LOADED);
        verify(planner).plan(eq(file), eq("file-3"));
    }

    @Test
    void anEventWithoutFileIdCannotSucceedOnRetrySoItIsParkedAtOnce() {
        var broken = new FileLoaded(0, "x.xlsx", null, 0, 0, 0);

        assertThatThrownBy(() -> subscription.handler().handle(event(broken))).isInstanceOf(NonRetryableEventException.class);
        verify(planner, never()).plan(any(), any());
    }
}
