package com.bank.upgrade.common.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;

/**
 * The JSON every Kafka message carries. {@code data} is the event-specific content; the rest is the
 * same for all events so de-duplication, tracing and audit work without knowing the content.
 *
 * @param version       envelope version, bump when a field is added or changes meaning
 * @param eventId       unique per event; consumers de-duplicate on it
 * @param eventType     for example {@code AccountUpgraded}
 * @param correlationId follows one business action through every service
 * @param actor         a user's email, or {@code system:<name>}
 */
public record EventEnvelope(
        int version,
        String eventId,
        String eventType,
        String correlationId,
        Instant occurredAt,
        String actor,
        JsonNode data) {

    public static final int CURRENT_VERSION = 1;

    public <T> T dataAs(ObjectMapper mapper, Class<T> type) {
        try {
            return mapper.treeToValue(data, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Event " + eventId + " has unreadable data for " + type.getSimpleName(), e);
        }
    }
}
