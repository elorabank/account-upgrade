package com.bank.upgrade.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.bank.upgrade.common.persistence.outbox.OutboxEvent;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;

/**
 * Runs only against the secured local broker (docker compose --profile secure), with the settings supplied the
 * way a deployed service gets them: BANK_KAFKA_* environment variables.
 * <pre>
 * source infra/kafka-secure/app.env
 * KAFKA_SECURE_TEST=true mvn -pl common test -Dtest=SecureKafkaTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "KAFKA_SECURE_TEST", matches = "true")
class SecureKafkaTest {

    /** The service's settings, read from the process environment plus that service's own login. */
    private static KafkaClientProperties asService(String service) {
        Map<String, Object> env = new HashMap<>();
        System.getenv().forEach((k, v) -> {
            if (k.startsWith("BANK_KAFKA_")) {
                env.put(k, v);
            }
        });
        env.put("BANK_KAFKA_SASL_USERNAME", service);
        env.put("BANK_KAFKA_SASL_PASSWORD", service + "-kafka-local-pw");
        env.put("BANK_KAFKA_GROUP_ID", service);
        var source = new SystemEnvironmentPropertySource("systemEnvironment", env);
        return new Binder(ConfigurationPropertySources.from(source))
            .bind("bank.kafka", Bindable.of(KafkaClientProperties.class)).get();
    }

    private static OutboxEvent event(String topic) {
        var e = new OutboxEvent();
        e.setTopic(topic);
        e.setMsgKey("acc-1");
        e.setEventType("SecureProbe");
        e.setCorrelationId("corr-secure");
        e.setPayload("{\"probe\":\"" + UUID.randomUUID() + "\"}");
        return e;
    }

    private static KafkaOutboxSender sender(KafkaClientProperties props) {
        KafkaSender<String, String> kafka = KafkaSender.create(SenderOptions.<String, String>create(props.producerConfig())
            .maxInFlight(1));
        return new KafkaOutboxSender(kafka, Duration.ofSeconds(20));
    }

    @Test
    void serviceCanPublishToItsOwnTopicButNotToOthers() {
        var ingestion = sender(asService("file-ingestion-service"));
        ingestion.send(List.of(event("account.file-loaded")));

        assertThatThrownBy(() -> ingestion.send(List.of(event("account.upgraded"))))
            .hasStackTraceContaining("TopicAuthorizationException");
    }

    @Test
    void wrongPasswordIsRejected() {
        var props = asService("file-ingestion-service");
        var bad = new KafkaClientProperties(props.bootstrapServers(), props.groupId(), props.properties(), props.producer(),
            props.consumer(), new KafkaClientProperties.Sasl("file-ingestion-service", "not-the-password", "SCRAM-SHA-512"));
        assertThatThrownBy(() -> sender(bad).send(List.of(event("account.file-loaded"))))
            .hasStackTraceContaining("Authentication");
    }

    @Test
    void auditCanReadWhatIngestionPublishedAndLetterCannot() {
        sender(asService("file-ingestion-service")).send(List.of(event("account.file-loaded")));

        var audit = asService("audit-service");
        var received = new AtomicReference<Boolean>(false);
        try (var consumer = new KafkaConsumer<String, String>(audit.consumerConfig())) {
            consumer.subscribe(List.of("account.file-loaded"));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                if (!consumer.poll(Duration.ofMillis(500)).isEmpty()) {
                    received.set(true);
                }
                return received.get();
            });
        }
        assertThat(received.get()).isTrue();

        try (var consumer = new KafkaConsumer<String, String>(asService("communication-service").consumerConfig())) {
            consumer.subscribe(List.of("account.file-loaded"));
            assertThatThrownBy(() -> {
                for (int i = 0; i < 20; i++) {
                    consumer.poll(Duration.ofMillis(500));
                }
            }).hasStackTraceContaining("AuthorizationException");
        }
    }
}
