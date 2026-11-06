package com.bank.upgrade.upgrade.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.upgrade.common.events.EventEnvelope;
import com.bank.upgrade.common.events.UpgradeRunRequested;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;

/** "Run now" as it reaches a copy: the run id is handed on; anything unreadable is skipped. */
class RunRequestListenerTest {

    final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    final List<String> started = new CopyOnWriteArrayList<>();
    final RunRequestListener listener = new RunRequestListener(
        Map.<String, Object>of(ConsumerConfig.GROUP_ID_CONFIG, "replaced"), "account-upgrade-service-copy-1-abc123", mapper, started::add);

    @Test
    void aRunRequestStartsThatRun() throws Exception {
        var event = new EventEnvelope(1, "e1", "UpgradeRunRequested", "corr", Instant.now(), "olivia@bank.example",
            mapper.valueToTree(new UpgradeRunRequested("run-42")));

        listener.onMessage(mapper.writeValueAsString(event));

        assertThat(started).containsExactly("run-42");
    }

    @Test
    void anUnreadableMessageIsSkipped() {
        listener.onMessage("not an event");
        listener.onMessage("{\"eventId\":\"e2\"}");

        assertThat(started).isEmpty();
    }
}
