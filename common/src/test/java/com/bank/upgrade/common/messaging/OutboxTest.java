package com.bank.upgrade.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bank.upgrade.common.correlation.CorrelationId;
import com.bank.upgrade.common.events.EventEnvelope;
import com.bank.upgrade.common.persistence.outbox.OutboxEvent;
import com.bank.upgrade.common.persistence.outbox.OutboxEventRepository;
import com.bank.upgrade.test.NoOpTransactionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.reactivestreams.Publisher;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;
import reactor.kafka.sender.SenderResult;

/** The outbox: writing an event, the poller that publishes it, and the Kafka sender; database and Kafka mocked. */
class OutboxTest {

    final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    final OutboxEventRepository repository = mock(OutboxEventRepository.class);

    private static OutboxEvent event(String topic, String key) {
        var e = new OutboxEvent();
        e.setTopic(topic);
        e.setMsgKey(key);
        e.setEventType("AccountUpgraded");
        e.setCorrelationId("corr-1");
        e.setPayload("{}");
        return e;
    }

    // --- writing ------------------------------------------------------------------------------------------

    @Test
    void anEventIsWrittenWithItsEnvelopeTheCurrentCorrelationIdAndTheServiceAsActor() throws Exception {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var outbox = new OutboxService(repository, mapper, "account-upgrade-service");

        OutboxEvent saved;
        try (var scope = CorrelationId.open("file-42")) {
            saved = outbox.publish("account.upgraded", "ACC-1", "AccountUpgraded", "ACC-1", Map.of("accountNo", "ACC-1"), null);
        }

        assertThat(saved.getTopic()).isEqualTo("account.upgraded");
        assertThat(saved.getMsgKey()).isEqualTo("ACC-1");
        assertThat(saved.getStatus()).isEqualTo(OutboxEvent.NEW);
        assertThat(saved.getCorrelationId()).isEqualTo("file-42");
        var envelope = mapper.readValue(saved.getPayload(), EventEnvelope.class);
        assertThat(envelope.eventId()).isEqualTo(saved.getEventId());
        assertThat(envelope.actor()).isEqualTo("system:account-upgrade-service");
        assertThat(envelope.data().get("accountNo").asText()).isEqualTo("ACC-1");
    }

    @Test
    void aPersonCausingTheEventIsKeptAsActor() throws Exception {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var saved = new OutboxService(repository, mapper, "svc")
            .publish("audit.user-action", "x", "UserAction", "x", Map.of(), "olivia@bank.example");
        assertThat(mapper.readValue(saved.getPayload(), EventEnvelope.class).actor()).isEqualTo("olivia@bank.example");
    }

    // --- publishing -----------------------------------------------------------------------------------------

    private OutboxPoller poller(OutboxSender sender, NoOpTransactionManager tx, SimpleMeterRegistry meters, int batchSize) {
        return new OutboxPoller(repository, sender, new TransactionTemplate(tx),
            new OutboxProperties(Duration.ofSeconds(1), batchSize, Duration.ofDays(7), Duration.ofSeconds(1)), meters);
    }

    @Test
    void aSentBatchIsMarkedPublishedAndCountedPerTopic() {
        var batch = List.of(event("account.upgraded", "A"), event("account.upgraded", "B"), event("account.upgrade-failed", "C"));
        when(repository.lockNextBatch(anyInt())).thenReturn(batch);
        var sender = mock(OutboxSender.class);
        var meters = new SimpleMeterRegistry();
        var tx = new NoOpTransactionManager();

        assertThat(poller(sender, tx, meters, 100).publishOneBatch()).isEqualTo(3);

        verify(sender).send(batch);
        assertThat(batch).allMatch(e -> OutboxEvent.PUBLISHED.equals(e.getStatus()) && e.getPublishedAt() != null);
        assertThat(tx.commits).hasValue(1);
        assertThat(meters.get("bank.outbox.published").tag("topic", "account.upgraded").counter().count()).isEqualTo(2);
        assertThat(meters.get("bank.outbox.published").tag("topic", "account.upgrade-failed").counter().count()).isEqualTo(1);
    }

    @Test
    void whenKafkaRefusesNothingIsMarkedAndTheTransactionRollsBack() {
        var batch = List.of(event("account.upgraded", "A"));
        when(repository.lockNextBatch(anyInt())).thenReturn(batch);
        var sender = mock(OutboxSender.class);
        doThrow(new IllegalStateException("Kafka down")).when(sender).send(any());
        var tx = new NoOpTransactionManager();

        assertThatThrownBy(() -> poller(sender, tx, new SimpleMeterRegistry(), 100).publishOneBatch())
            .hasMessageContaining("Kafka down");

        assertThat(batch.get(0).getStatus()).isEqualTo(OutboxEvent.NEW);
        assertThat(tx.rollbacks).hasValue(1);
    }

    @Test
    void drainingStopsAtTheFirstBatchThatIsNotFull() {
        when(repository.lockNextBatch(anyInt()))
            .thenReturn(List.of(event("t", "1"), event("t", "2")))
            .thenReturn(List.of(event("t", "3"), event("t", "4")))
            .thenReturn(List.of(event("t", "5")));
        var sender = mock(OutboxSender.class);

        poller(sender, new NoOpTransactionManager(), new SimpleMeterRegistry(), 2).drain();

        verify(repository, times(3)).lockNextBatch(2);
        verify(sender, times(3)).send(any());
    }

    @Test
    void theBacklogGaugesShowWhatWaitsAndForHowLong() {
        when(repository.countByStatus(OutboxEvent.NEW)).thenReturn(42L);
        when(repository.findOldestNewCreatedAt()).thenReturn(Instant.now().minusSeconds(90));
        var meters = new SimpleMeterRegistry();
        var poller = poller(mock(OutboxSender.class), new NoOpTransactionManager(), meters, 100);

        poller.measure();
        assertThat(meters.get("bank.outbox.waiting").gauge().value()).isEqualTo(42);
        assertThat(meters.get("bank.outbox.oldest.age").gauge().value()).isBetween(89.0, 92.0);

        when(repository.countByStatus(OutboxEvent.NEW)).thenReturn(0L);
        when(repository.findOldestNewCreatedAt()).thenReturn(null);
        poller.measure();
        assertThat(meters.get("bank.outbox.oldest.age").gauge().value()).isZero();
    }

    // --- the Kafka sender -----------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static KafkaSender<String, String> kafka(List<ProducerRecord<String, String>> sent, Exception rejectWith) {
        KafkaSender<String, String> sender = mock(KafkaSender.class);
        when(sender.send(any(Publisher.class))).thenAnswer(inv -> Flux.from(inv.<Publisher<SenderRecord<String, String, String>>>getArgument(0))
            .map(record -> {
                sent.add(record);
                SenderResult<String> result = mock(SenderResult.class);
                when(result.exception()).thenReturn(rejectWith);
                when(result.correlationMetadata()).thenReturn(record.correlationMetadata());
                return result;
            }));
        return sender;
    }

    @Test
    void eachEventGoesToItsTopicWithItsKeyAndHeaders() {
        var sent = new ArrayList<ProducerRecord<String, String>>();
        var e = event("account.upgraded", "ACC-9");

        new KafkaOutboxSender(kafka(sent, null), Duration.ofSeconds(1)).send(List.of(e));

        var record = sent.get(0);
        assertThat(record.topic()).isEqualTo("account.upgraded");
        assertThat(record.key()).isEqualTo("ACC-9");
        assertThat(record.value()).isEqualTo("{}");
        assertThat(new String(record.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8)).isEqualTo(e.getEventId());
        assertThat(new String(record.headers().lastHeader(CorrelationId.KAFKA_HEADER).value(), StandardCharsets.UTF_8)).isEqualTo("corr-1");
    }

    @Test
    void aRejectedEventFailsTheWholeSendSoTheBatchIsRetried() {
        var sender = new KafkaOutboxSender(kafka(new ArrayList<>(), new RuntimeException("not leader")), Duration.ofSeconds(1));
        assertThatThrownBy(() -> sender.send(List.of(event("t", "k")))).hasMessageContaining("rejected");
    }

    @Test
    void theCorrelationIdTravelsWithTheEvent() {
        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        when(repository.save(saved.capture())).thenAnswer(inv -> inv.getArgument(0));
        try (var scope = CorrelationId.open("upload-7")) {
            new OutboxService(repository, mapper, "svc").publish("t", "k", "T", "k", Map.of(), null);
        }
        assertThat(saved.getValue().getCorrelationId()).isEqualTo("upload-7");
    }
}
