package com.bank.upgrade.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bank.upgrade.common.events.EventEnvelope;
import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.events.UpgradeRunRequested;
import com.bank.upgrade.test.KafkaTestContainer;
import com.bank.upgrade.upgrade.run.RunRequestListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

/** "Run now" is a broadcast: every copy, each in its own consumer group, hears every request. */
class RunRequestBroadcastTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private Map<String, Object> consumerConfig() {
        return Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainer.bootstrapServers(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.GROUP_ID_CONFIG, "replaced-by-the-listener",
            ConsumerConfig.METADATA_MAX_AGE_CONFIG, 1000);
    }

    private void publish(KafkaProducer<String, String> producer, String runId) throws Exception {
        var envelope = new EventEnvelope(1, UUID.randomUUID().toString(), "UpgradeRunRequested", "corr", Instant.now(),
            "pat@bank.test", mapper.valueToTree(new UpgradeRunRequested(runId)));
        producer.send(new ProducerRecord<>(Topics.UPGRADE_RUN_REQUESTED, runId, mapper.writeValueAsString(envelope))).get();
    }

    @Test
    void everyCopyHearsEveryRunRequest() throws Exception {
        Set<String> heardByA = new CopyOnWriteArraySet<>();
        Set<String> heardByB = new CopyOnWriteArraySet<>();
        var copyA = new RunRequestListener(consumerConfig(), "account-upgrade-service-copy-A", mapper, heardByA::add);
        var copyB = new RunRequestListener(consumerConfig(), "account-upgrade-service-copy-B", mapper, heardByB::add);
        copyA.start();
        copyB.start();
        try (var producer = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainer.bootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            // The listeners start at the end of the topic, so keep announcing until both have joined and heard one
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).until(() -> {
                publish(producer, "run-" + UUID.randomUUID());
                return heardByA.stream().anyMatch(heardByB::contains);
            });
        } finally {
            copyA.stop();
            copyB.stop();
        }
        List<String> both = heardByA.stream().filter(heardByB::contains).toList();
        assertThat(both).as("the same request reached both copies").isNotEmpty();
    }
}
