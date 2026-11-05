package com.bank.upgrade.common.messaging;

import com.bank.upgrade.common.correlation.CorrelationId;
import com.bank.upgrade.common.events.EventEnvelope;
import com.bank.upgrade.common.persistence.outbox.OutboxEvent;
import com.bank.upgrade.common.persistence.outbox.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records an event in the same database transaction as the business change. If that transaction rolls back
 * the event disappears with it; if it commits, {@link OutboxPoller} publishes it to Kafka.
 */
public class OutboxService {

    private final OutboxEventRepository repository;
    private final ObjectMapper mapper;
    private final String serviceName;

    public OutboxService(OutboxEventRepository repository, ObjectMapper mapper, String serviceName) {
        this.repository = repository;
        this.mapper = mapper;
        this.serviceName = serviceName;
    }

    /**
     * @param key         Kafka message key; events with the same key stay in order (for example the account number)
     * @param aggregateId what the event is about, kept for support queries
     * @param actor       who caused it (a user's email, or {@code system:<name>}); null means this service
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent publish(String topic, String key, String eventType, String aggregateId, Object data, String actor) {
        var envelope = new EventEnvelope(
            EventEnvelope.CURRENT_VERSION,
            UUID.randomUUID().toString(),
            eventType,
            CorrelationId.currentOrNew(),
            Instant.now(),
            actor != null ? actor : "system:" + serviceName,
            mapper.valueToTree(data));

        var event = new OutboxEvent();
        event.setEventId(envelope.eventId());
        event.setTopic(topic);
        event.setMsgKey(key);
        event.setEventType(eventType);
        event.setAggregateId(aggregateId);
        event.setCorrelationId(envelope.correlationId());
        try {
            event.setPayload(mapper.writeValueAsString(envelope));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Event data for " + eventType + " cannot be written as JSON", e);
        }
        return repository.save(event);
    }
}
