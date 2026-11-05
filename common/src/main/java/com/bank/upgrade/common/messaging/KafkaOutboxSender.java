package com.bank.upgrade.common.messaging;

import com.bank.upgrade.common.correlation.CorrelationId;
import com.bank.upgrade.common.persistence.outbox.OutboxEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import reactor.core.publisher.Flux;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;
import reactor.kafka.sender.SenderResult;

/** Sends outbox events with {@code acks=all} (set in the producer settings) and waits until Kafka has confirmed all of them. */
public class KafkaOutboxSender implements OutboxSender {

    private final KafkaSender<String, String> sender;
    private final Duration timeout;

    public KafkaOutboxSender(KafkaSender<String, String> sender, Duration timeout) {
        this.sender = sender;
        this.timeout = timeout;
    }

    @Override
    public void send(List<OutboxEvent> batch) {
        List<SenderRecord<String, String, String>> records = batch.stream()
            .map(e -> SenderRecord.create(toRecord(e), e.getEventId()))
            .toList();
        // called on a boundedElastic thread inside the polling transaction, so blocking here is intended
        List<SenderResult<String>> results = sender.send(Flux.fromIterable(records)).collectList().block(timeout);
        if (results == null || results.size() != records.size()) {
            throw new IllegalStateException("Kafka confirmed " + (results == null ? 0 : results.size()) + " of " + records.size() + " events");
        }
        for (SenderResult<String> result : results) {
            if (result.exception() != null) {
                throw new IllegalStateException("Kafka rejected event " + result.correlationMetadata(), result.exception());
            }
        }
    }

    private static ProducerRecord<String, String> toRecord(OutboxEvent e) {
        List<Header> headers = List.of(
            header("eventId", e.getEventId()),
            header("eventType", e.getEventType()),
            header(CorrelationId.KAFKA_HEADER, e.getCorrelationId()));
        return new ProducerRecord<>(e.getTopic(), null, e.getMsgKey(), e.getPayload(), headers);
    }

    private static Header header(String name, String value) {
        return new RecordHeader(name, value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
    }
}
