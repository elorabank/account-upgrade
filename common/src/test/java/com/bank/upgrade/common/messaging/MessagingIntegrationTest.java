package com.bank.upgrade.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.bank.upgrade.common.correlation.CorrelationId;
import com.bank.upgrade.common.events.EventEnvelope;
import com.bank.upgrade.common.persistence.BlockingJpa;
import com.bank.upgrade.common.persistence.outbox.OutboxEvent;
import com.bank.upgrade.common.persistence.outbox.OutboxEventRepository;
import com.bank.upgrade.common.persistence.processed.ProcessedEventRepository;
import com.bank.upgrade.test.KafkaTestContainer;
import com.bank.upgrade.test.OracleTestConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;

/**
 * Real Oracle + real Kafka. Covers the outbox (transactional write, ordering, retry after a failed send)
 * and the consumer template (de-duplication, retry, dead-letter topic, correlation id).
 */
@SpringBootTest(
    classes = MessagingIntegrationTest.App.class,
    args = "--spring.config.import=classpath:bank-persistence-defaults.yml,classpath:bank-messaging-defaults.yml",
    properties = {
        "spring.main.web-application-type=none",
        "spring.application.name=common-it",
        "bank.outbox.enabled=true",
        "bank.processed-events.enabled=true",
        "bank.outbox.poll-interval=PT0.2S",
        "bank.consumer.max-attempts=3",
        "bank.consumer.backoff-initial=PT0.05S",
        "bank.consumer.backoff-max=PT0.2S"
    })
class MessagingIntegrationTest {

    static final String IN_DEDUPE = "it.dedupe";
    static final String IN_RETRY = "it.retry";
    static final String IN_FAIL = "it.fail";
    static final String IN_CHAIN = "it.chain.in";
    static final String OUT_CHAIN = "it.chain.out";
    static final String IN_PARALLEL = "it.parallel";

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("bank.kafka.bootstrap-servers", KafkaTestContainer::bootstrapServers);
        // Created before the consumer starts: a topic with 3 partitions, to see them handled side by side
        try (var admin = org.apache.kafka.clients.admin.AdminClient.create(Map.of("bootstrap.servers", KafkaTestContainer.bootstrapServers()))) {
            admin.createTopics(List.of(new org.apache.kafka.clients.admin.NewTopic(IN_PARALLEL, 3, (short) 1))).all().get();
        } catch (Exception e) {
            if (!(e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Counters the handlers write to and the tests read from. */
    static class Probe {
        final AtomicInteger dedupeCalls = new AtomicInteger();
        final AtomicInteger retryAttempts = new AtomicInteger();
        final Map<String, AtomicInteger> failAttempts = new ConcurrentHashMap<>();
        final List<String> failTopicHandled = new CopyOnWriteArrayList<>();
        final AtomicReference<String> chainCorrelationInHandler = new AtomicReference<>();
        final AtomicInteger parallelInFlight = new AtomicInteger();
        final AtomicInteger parallelMaxInFlight = new AtomicInteger();
        final List<String> parallelHandled = new CopyOnWriteArrayList<>();
    }

    /** Wraps the real sender so a test can make Kafka "reject" events. */
    static class ToggleSender implements OutboxSender {
        final KafkaOutboxSender real;
        volatile boolean failing;
        final AtomicInteger attempts = new AtomicInteger();

        ToggleSender(KafkaOutboxSender real) {
            this.real = real;
        }

        @Override
        public void send(List<OutboxEvent> batch) {
            attempts.incrementAndGet();
            if (failing) {
                throw new IllegalStateException("Kafka unavailable (simulated)");
            }
            real.send(batch);
        }
    }

    @Configuration
    @EnableAutoConfiguration
    @Import(OracleTestConfig.class)
    static class App {

        @Bean
        Probe probe() {
            return new Probe();
        }

        @Bean
        ToggleSender toggleSender(KafkaSender<String, String> kafkaSender) {
            return new ToggleSender(new KafkaOutboxSender(kafkaSender, Duration.ofSeconds(30)));
        }

        @Bean
        EventSubscription parallel(Probe probe) {
            return new EventSubscription(IN_PARALLEL, event -> {
                probe.parallelMaxInFlight.accumulateAndGet(probe.parallelInFlight.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(300);
                } finally {
                    probe.parallelInFlight.decrementAndGet();
                }
                probe.parallelHandled.add(event.eventType());
            });
        }

        @Bean
        EventSubscription dedupe(Probe probe) {
            return new EventSubscription(IN_DEDUPE, event -> probe.dedupeCalls.incrementAndGet());
        }

        @Bean
        EventSubscription retry(Probe probe) {
            return new EventSubscription(IN_RETRY, event -> {
                if (probe.retryAttempts.incrementAndGet() < 3) {
                    throw new IllegalStateException("transient failure " + probe.retryAttempts.get());
                }
            });
        }

        @Bean
        EventSubscription failing(Probe probe) {
            return new EventSubscription(IN_FAIL, event -> {
                probe.failAttempts.computeIfAbsent(event.eventId(), k -> new AtomicInteger()).incrementAndGet();
                if ("Bad".equals(event.eventType())) {
                    throw new IllegalStateException("always fails");
                }
                probe.failTopicHandled.add(event.eventId());
            });
        }

        @Bean
        EventSubscription chain(Probe probe, OutboxService outbox) {
            return new EventSubscription(IN_CHAIN, event -> {
                probe.chainCorrelationInHandler.set(CorrelationId.current());
                outbox.publish(OUT_CHAIN, "acc-1", "ChainOut", "acc-1", Map.of("from", event.eventId()), event.actor());
            });
        }
    }

    @Autowired OutboxService outbox;
    @Autowired OutboxEventRepository outboxRepository;
    @Autowired ProcessedEventRepository processed;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired ToggleSender toggle;
    @Autowired Probe probe;
    @Autowired ObjectMapper mapper;
    @Autowired BlockingJpa blockingJpa;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    // ---------- helpers ----------

    private static String unique(String prefix) {
        return prefix + "." + UUID.randomUUID();
    }

    private static Properties base() {
        var p = new Properties();
        p.put("bootstrap.servers", KafkaTestContainer.bootstrapServers());
        return p;
    }

    private static List<ConsumerRecord<String, String>> read(String topic, int expected) {
        var p = base();
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        var found = new ArrayList<ConsumerRecord<String, String>>();
        try (var consumer = new KafkaConsumer<String, String>(p)) {
            consumer.subscribe(List.of(topic));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(found::add);
                return found.size() >= expected;
            });
        }
        return found;
    }

    /** Waits for a message on {@code topic} that satisfies {@code wanted}; other messages on the topic are ignored. */
    private static ConsumerRecord<String, String> readMatching(String topic, java.util.function.Predicate<ConsumerRecord<String, String>> wanted) {
        var p = base();
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        var match = new AtomicReference<ConsumerRecord<String, String>>();
        try (var consumer = new KafkaConsumer<String, String>(p)) {
            consumer.subscribe(List.of(topic));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    if (wanted.test(record)) {
                        match.set(record);
                    }
                }
                return match.get() != null;
            });
        }
        return match.get();
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private String envelope(String eventId, String type, String correlationId) throws Exception {
        return mapper.writeValueAsString(new EventEnvelope(1, eventId, type, correlationId, Instant.now(), "system:test",
            mapper.valueToTree(Map.of("hello", "world"))));
    }

    private void produceTo(String topic, int partition, String key, String value) {
        var p = base();
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (var producer = new KafkaProducer<String, String>(p)) {
            producer.send(new ProducerRecord<>(topic, partition, key, value));
            producer.flush();
        }
    }

    private void produce(String topic, String key, String value, String correlationHeader) {
        var p = base();
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (var producer = new KafkaProducer<String, String>(p)) {
            var record = new ProducerRecord<String, String>(topic, key, value);
            if (correlationHeader != null) {
                record.headers().add(new RecordHeader(CorrelationId.KAFKA_HEADER, correlationHeader.getBytes(StandardCharsets.UTF_8)));
            }
            producer.send(record);
            producer.flush();
        }
    }

    // ---------- outbox ----------

    @Test
    void committedEventIsPublishedWithHeadersAndMarkedPublished() throws Exception {
        String topic = unique("it.out");
        OutboxEvent saved;
        try (var scope = CorrelationId.open("corr-outbox-1")) {
            saved = tx().execute(s -> outbox.publish(topic, "acc-42", "AccountUpgraded", "acc-42", Map.of("to", "PREMIUM"), "pat@bank.test"));
        }

        var records = read(topic, 1);
        var record = records.get(0);
        assertThat(record.key()).isEqualTo("acc-42");
        assertThat(header(record, "eventId")).isEqualTo(saved.getEventId());
        assertThat(header(record, "eventType")).isEqualTo("AccountUpgraded");
        assertThat(header(record, CorrelationId.KAFKA_HEADER)).isEqualTo("corr-outbox-1");

        var envelope = mapper.readValue(record.value(), EventEnvelope.class);
        assertThat(envelope.eventId()).isEqualTo(saved.getEventId());
        assertThat(envelope.correlationId()).isEqualTo("corr-outbox-1");
        assertThat(envelope.actor()).isEqualTo("pat@bank.test");
        assertThat(envelope.data().get("to").asText()).isEqualTo("PREMIUM");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(outboxRepository.findById(saved.getId()).orElseThrow().getStatus()).isEqualTo(OutboxEvent.PUBLISHED));
    }

    @Test
    void rolledBackTransactionPublishesNothing() throws Exception {
        String topic = unique("it.rollback");
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> {
            outbox.publish(topic, "k", "Ghost", "k", Map.of(), null);
            throw new IllegalStateException("business change failed");
        })).hasMessageContaining("business change failed");

        Thread.sleep(1500); // several poll rounds
        assertThat(outboxRepository.findAll()).noneMatch(e -> topic.equals(e.getTopic()));
        // and a later committed event on the same topic is the only message
        tx().execute(s -> outbox.publish(topic, "k", "Real", "k", Map.of(), null));
        var records = read(topic, 1);
        assertThat(records).hasSize(1);
        assertThat(header(records.get(0), "eventType")).isEqualTo("Real");
    }

    @Test
    void publishOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> outbox.publish("t", "k", "X", "k", Map.of(), null))
            .hasMessageContaining("transaction");
    }

    @Test
    void eventsWithTheSameKeyKeepTheirOrder() {
        String topic = unique("it.order");
        tx().executeWithoutResult(s -> {
            for (int i = 0; i < 30; i++) {
                outbox.publish(topic, "acc-1", "Step", "acc-1", Map.of("n", i), null);
            }
        });
        var records = read(topic, 30);
        List<Integer> sequence = records.stream().map(r -> {
            try {
                return mapper.readValue(r.value(), EventEnvelope.class).data().get("n").asInt();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
        assertThat(sequence).isSorted().hasSize(30);
    }

    @Test
    void failedSendKeepsTheEventAndRetriesUntilKafkaAccepts() {
        String topic = unique("it.flaky");
        toggle.failing = true;
        try {
            OutboxEvent saved = tx().execute(s -> outbox.publish(topic, "k", "Flaky", "k", Map.of(), null));
            int before = toggle.attempts.get();
            await().atMost(Duration.ofSeconds(10)).until(() -> toggle.attempts.get() >= before + 3);
            assertThat(outboxRepository.findById(saved.getId()).orElseThrow().getStatus()).isEqualTo(OutboxEvent.NEW);

            toggle.failing = false;
            assertThat(read(topic, 1)).hasSize(1);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(outboxRepository.findById(saved.getId()).orElseThrow().getStatus()).isEqualTo(OutboxEvent.PUBLISHED));
        } finally {
            toggle.failing = false;
        }
    }

    // ---------- consumer ----------

    @Test
    void partitionsAreHandledSideBySideAndEachInOrder() throws Exception {
        for (int i = 0; i < 6; i++) {
            int partition = i % 3;
            produceTo(IN_PARALLEL, partition, "key-" + partition, envelope(UUID.randomUUID().toString(), "P" + partition + "-" + i, null));
        }

        await().atMost(Duration.ofSeconds(20)).until(() -> probe.parallelHandled.size() == 6);

        // 6 handlers of 300 ms one after another would never overlap; per partition they do
        assertThat(probe.parallelMaxInFlight.get()).isBetween(2, 3);
        // within a partition the order is kept: P0-0 before P0-3, P1-1 before P1-4, P2-2 before P2-5
        var order = probe.parallelHandled;
        assertThat(order.indexOf("P0-0")).isLessThan(order.indexOf("P0-3"));
        assertThat(order.indexOf("P1-1")).isLessThan(order.indexOf("P1-4"));
        assertThat(order.indexOf("P2-2")).isLessThan(order.indexOf("P2-5"));
    }

    @Test
    void redeliveredEventIsHandledOnlyOnce() throws Exception {
        String id = UUID.randomUUID().toString();
        String message = envelope(id, "Dup", "corr-dup");
        produce(IN_DEDUPE, "k", message, null);
        produce(IN_DEDUPE, "k", message, null);
        String other = UUID.randomUUID().toString();
        produce(IN_DEDUPE, "k", envelope(other, "Other", "corr-dup"), null);

        await().atMost(Duration.ofSeconds(30)).until(() -> processed.existsById(other));
        assertThat(processed.existsById(id)).isTrue();
        assertThat(probe.dedupeCalls.get()).as("two distinct event ids, one of them sent twice").isEqualTo(2);
    }

    @Test
    void handlerThatFailsTwiceSucceedsOnTheThirdAttempt() throws Exception {
        String id = UUID.randomUUID().toString();
        produce(IN_RETRY, "k", envelope(id, "Flaky", null), null);
        await().atMost(Duration.ofSeconds(30)).until(() -> processed.existsById(id));
        assertThat(probe.retryAttempts.get()).isEqualTo(3);
    }

    @Test
    void exhaustedRetriesParkTheMessageAndTheConsumerMovesOn() throws Exception {
        String bad = UUID.randomUUID().toString();
        String good = UUID.randomUUID().toString();
        produce(IN_FAIL, "k", envelope(bad, "Bad", "corr-dlt"), null);
        produce(IN_FAIL, "k", envelope(good, "Good", null), null);

        var parked = readMatching("it.fail.DLT", r -> r.value().contains(bad));
        assertThat(header(parked, "dlt-original-topic")).isEqualTo(IN_FAIL);
        assertThat(header(parked, "dlt-exception-message")).contains("always fails");
        assertThat(parked.value()).as("the original message, with its correlation id, is kept untouched").contains("corr-dlt");

        await().atMost(Duration.ofSeconds(30)).until(() -> probe.failTopicHandled.contains(good));
        // once inside its batch, then max-attempts=3 on its own before it is parked
        assertThat(probe.failAttempts.get(bad).get()).isBetween(3, 4);
        assertThat(processed.existsById(bad)).as("a failed event is not marked processed").isFalse();
    }

    @Test
    void unreadableMessageGoesStraightToTheDeadLetterTopic() throws Exception {
        produce(IN_FAIL, "k", "this is not json", null);
        var parked = readMatching("it.fail.DLT", r -> "this is not json".equals(r.value()));
        assertThat(header(parked, "dlt-exception-class")).endsWith("NonRetryableEventException");
    }

    @Test
    void correlationIdFlowsFromTheKafkaMessageThroughTheHandlerIntoTheNextEvent() throws Exception {
        String id = UUID.randomUUID().toString();
        produce(IN_CHAIN, "acc-1", envelope(id, "Start", "corr-chain-77"), null);

        var next = readMatching(OUT_CHAIN, r -> r.value().contains(id));
        assertThat(probe.chainCorrelationInHandler.get()).isEqualTo("corr-chain-77");
        assertThat(header(next, CorrelationId.KAFKA_HEADER)).isEqualTo("corr-chain-77");
        assertThat(mapper.readValue(next.value(), EventEnvelope.class).correlationId()).isEqualTo("corr-chain-77");
    }

    // ---------- blocking JPA helper ----------

    @Test
    void blockingJpaRunsOffTheEventLoopWithTheCorrelationIdFromTheContext() {
        var thread = new AtomicReference<String>();
        var correlation = new AtomicReference<String>();
        blockingJpa.call(() -> {
            thread.set(Thread.currentThread().getName());
            correlation.set(CorrelationId.current());
            return outboxRepository.count();
        }).contextWrite(ctx -> ctx.put(CorrelationId.CONTEXT_KEY, "corr-from-context")).block();

        assertThat(thread.get()).contains("boundedElastic");
        assertThat(correlation.get()).isEqualTo("corr-from-context");
        assertThat(CorrelationId.current()).as("MDC is cleaned up").isNull();
    }

    @Test
    void blockingJpaTransactionRollsBackOnFailure() {
        String topic = unique("it.bj");
        assertThatThrownBy(() -> blockingJpa.runInTransaction(() -> {
            outbox.publish(topic, "k", "X", "k", Map.of(), null);
            throw new IllegalStateException("boom");
        }).block()).hasMessageContaining("boom");
        assertThat(outboxRepository.findAll()).noneMatch(e -> topic.equals(e.getTopic()));

        blockingJpa.runInTransaction(() -> outbox.publish(topic, "k", "X", "k", Map.of(), null)).block();
        assertThat(outboxRepository.findAll()).anyMatch(e -> topic.equals(e.getTopic()));
    }
}
