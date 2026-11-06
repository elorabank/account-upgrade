package com.bank.upgrade.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bank.upgrade.common.correlation.CorrelationId;
import com.bank.upgrade.common.events.EventEnvelope;
import com.bank.upgrade.common.persistence.processed.ProcessedEvent;
import com.bank.upgrade.common.persistence.processed.ProcessedEventRepository;
import com.bank.upgrade.test.NoOpTransactionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.kafka.receiver.ReceiverOffset;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;
import reactor.kafka.sender.SenderResult;

/** The consumer's batch handling with Kafka and the database mocked: de-duplication, retries, dead letters. */
class EventConsumerTest {

    static final String TOPIC = "account.upgraded";
    final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    final ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
    final Set<String> processedIds = new HashSet<>();
    @SuppressWarnings("unchecked")
    final KafkaSender<String, String> deadLetters = mock(KafkaSender.class);
    final List<ProducerRecord<String, String>> parked = new CopyOnWriteArrayList<>();
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    /** Events whose handling committed, in order. */
    final List<String> handled = new CopyOnWriteArrayList<>();
    /** Handled in the open transaction; kept on commit, dropped on rollback, like a database's rows. */
    final List<String> pending = new CopyOnWriteArrayList<>();
    final List<String> correlationIdsSeen = new CopyOnWriteArrayList<>();
    EventHandler handler = event -> {
        pending.add(event.eventId());
        correlationIdsSeen.add(CorrelationId.current());
    };

    /** Commits or throws away what the handler and the processed-event table did, as a real transaction would. */
    final NoOpTransactionManager tx = new NoOpTransactionManager() {
        private Set<String> before;

        @Override
        public org.springframework.transaction.TransactionStatus getTransaction(
                org.springframework.transaction.TransactionDefinition definition) {
            before = new HashSet<>(processedIds);
            pending.clear();
            return super.getTransaction(definition);
        }

        @Override
        public void commit(org.springframework.transaction.TransactionStatus status) {
            handled.addAll(pending);
            pending.clear();
            super.commit(status);
        }

        @Override
        public void rollback(org.springframework.transaction.TransactionStatus status) {
            processedIds.retainAll(before);
            pending.clear();
            super.rollback(status);
        }
    };

    @BeforeEach
    @SuppressWarnings("unchecked")
    void mocks() {
        // The processed-event table, kept in a set
        when(processed.findAllById(anyIterable())).thenAnswer(inv -> {
            List<ProcessedEvent> found = new ArrayList<>();
            ((Iterable<String>) inv.getArgument(0)).forEach(id -> {
                if (processedIds.contains(id)) {
                    found.add(new ProcessedEvent(id, TOPIC));
                }
            });
            return found;
        });
        when(processed.existsById(anyString())).thenAnswer(inv -> processedIds.contains(inv.<String>getArgument(0)));
        when(processed.saveAndFlush(any())).thenAnswer(inv -> {
            processedIds.add(inv.<ProcessedEvent>getArgument(0).getEventId());
            return inv.getArgument(0);
        });
        when(processed.saveAllAndFlush(anyIterable())).thenAnswer(inv -> {
            ((Iterable<ProcessedEvent>) inv.getArgument(0)).forEach(p -> processedIds.add(p.getEventId()));
            return List.of();
        });
        // Every dead letter is accepted
        when(deadLetters.send(any(Publisher.class))).thenAnswer(inv -> Flux.from(inv.<Publisher<SenderRecord<String, String, Long>>>getArgument(0))
            .map(record -> {
                parked.add(record);
                SenderResult<Long> ok = mock(SenderResult.class);
                return ok;
            }));
    }

    private EventConsumer consumer() {
        return new EventConsumer(Map.of(TOPIC, event -> handler.handle(event)), Map.of(), deadLetters, processed,
            new TransactionTemplate(tx), mapper,
            new ConsumerProperties(3, Duration.ofMillis(1), Duration.ofMillis(5), 100, Duration.ofMillis(10)), meters);
    }

    private ReceiverRecord<String, String> record(String eventId, String correlationId, AtomicInteger acks) throws Exception {
        var envelope = new EventEnvelope(1, eventId, "AccountUpgraded", correlationId, Instant.now(), "system:test",
            mapper.createObjectNode().put("accountNo", "ACC-" + eventId));
        return raw(mapper.writeValueAsString(envelope), acks);
    }

    private static ReceiverRecord<String, String> raw(String value, AtomicInteger acks) {
        ReceiverOffset offset = mock(ReceiverOffset.class);
        org.mockito.Mockito.doAnswer(inv -> acks.incrementAndGet()).when(offset).acknowledge();
        return new ReceiverRecord<>(new ConsumerRecord<>(TOPIC, 0, 0, "key", value), offset);
    }

    private double consumed(String result) {
        var counter = meters.find("bank.events.consumed").tags("topic", TOPIC, "result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void aBatchIsHandledInOrderInOneTransactionThenAcknowledged() throws Exception {
        var acks = new AtomicInteger();
        consumer().processBatch(List.of(record("e1", "corr-1", acks), record("e2", "corr-2", acks), record("e3", "corr-3", acks))).block();

        assertThat(handled).containsExactly("e1", "e2", "e3");
        assertThat(processedIds).containsExactlyInAnyOrder("e1", "e2", "e3");
        assertThat(tx.commits).hasValue(1);
        assertThat(acks).hasValue(3);
        assertThat(consumed("processed")).isEqualTo(3);
    }

    @Test
    void anEventAlreadyProcessedIsSkippedAndARepeatInsideTheBatchToo() throws Exception {
        processedIds.add("old");
        var acks = new AtomicInteger();
        consumer().processBatch(List.of(record("old", "c", acks), record("new", "c", acks), record("new", "c", acks))).block();

        assertThat(handled).containsExactly("new");
        assertThat(acks).hasValue(3);
        assertThat(consumed("duplicate")).isEqualTo(2);
    }

    @Test
    void aHandlerThatFailsTwiceSucceedsOnTheThirdAttempt() throws Exception {
        var attempts = new AtomicInteger();
        handler = event -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("database busy");
            }
            pending.add(event.eventId());
        };
        var acks = new AtomicInteger();
        consumer().processBatch(List.of(record("e1", "c", acks))).block();

        assertThat(attempts).hasValue(3);
        assertThat(handled).containsExactly("e1");
        assertThat(parked).isEmpty();
        assertThat(acks).hasValue(1);
    }

    @Test
    void exhaustedRetriesParkTheMessageOnTheDeadLetterTopicWithTheReason() throws Exception {
        handler = event -> {
            throw new IllegalStateException("no template for this account type");
        };
        var acks = new AtomicInteger();
        consumer().processBatch(List.of(record("bad", "c", acks))).block();

        assertThat(parked).hasSize(1);
        var letter = parked.get(0);
        assertThat(letter.topic()).isEqualTo(TOPIC + ".DLT");
        assertThat(header(letter, "dlt-original-topic")).isEqualTo(TOPIC);
        assertThat(header(letter, "dlt-exception-message")).contains("no template");
        assertThat(acks).as("the consumer moves on").hasValue(1);
        assertThat(consumed("dead-lettered")).isEqualTo(1);
    }

    @Test
    void anUnreadableMessageGoesStraightToTheDeadLetterTopicWithoutRetries() {
        var acks = new AtomicInteger();
        consumer().processBatch(List.of(raw("this is not json", acks))).block();

        assertThat(parked).hasSize(1);
        assertThat(handled).isEmpty();
        assertThat(tx.commits).as("never even tried").hasValue(0);
    }

    @Test
    void whenABatchFailsItsGoodEventsAreStillHandledOneByOne() throws Exception {
        handler = event -> {
            pending.add(event.eventId());
            if (event.eventId().equals("bad")) {
                throw new IllegalStateException("broken");
            }
        };
        var acks = new AtomicInteger();
        consumer().processBatch(List.of(record("good-1", "c", acks), record("bad", "c", acks), record("good-2", "c", acks))).block();

        // The batch rolled back as a whole; one by one, the good ones commit and the bad one is parked
        assertThat(handled).containsExactly("good-1", "good-2");
        assertThat(tx.rollbacks).as("the batch, then each try of the bad one").hasValueGreaterThanOrEqualTo(2);
        assertThat(parked).extracting(ProducerRecord::value).singleElement().asString().contains("\"bad\"");
        assertThat(acks).hasValue(3);
    }

    @Test
    void theHandlerRunsUnderTheEventsCorrelationId() throws Exception {
        var acks = new AtomicInteger();
        consumer().processBatch(List.of(record("e1", "file-7", acks), record("e2", "file-8", acks))).block();
        consumer().processBatch(List.of(record("e3", "file-9", acks))).block();

        assertThat(correlationIdsSeen).containsExactly("file-7", "file-8", "file-9");
    }

    @Test
    void nothingIsParkedWhenEverythingWorks() throws Exception {
        consumer().processBatch(List.of(record("e1", "c", new AtomicInteger()))).block();
        verify(deadLetters, never()).send(any(Publisher.class));
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
