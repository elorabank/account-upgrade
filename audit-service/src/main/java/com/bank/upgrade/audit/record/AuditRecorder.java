package com.bank.upgrade.audit.record;

import com.bank.upgrade.audit.domain.AuditEvent;
import com.bank.upgrade.audit.domain.AuditEventRepository;
import com.bank.upgrade.common.events.EventEnvelope;
import com.bank.upgrade.common.events.Topics;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes one AUDIT_EVENT row per event: who (actor), what (type and a short summary), when, for which account and
 * which business action (correlation id). The row is kept forever and can never be changed, so it gets no customer
 * names or emails: the summary is built from codes and counts, and anything that looks like an email is masked.
 * <p>
 * Reads the event data as JSON rather than as the producers' classes, so a field added later does not break it.
 */
public class AuditRecorder {

    private static final Pattern EMAIL = Pattern.compile("[^\\s@<>\"']+@[^\\s@<>\"']+");

    /** Which service publishes each topic. User actions come from more than one, so they have none. */
    private static final Map<String, String> SOURCE = Map.of(
        Topics.FILE_LOADED, "file-ingestion-service",
        Topics.ACCOUNT_UPGRADED, "account-upgrade-service",
        Topics.UPGRADE_FAILED, "account-upgrade-service",
        Topics.UPGRADE_REJECTED, "account-upgrade-service",
        Topics.NOTIFICATION_SENT, "communication-service",
        Topics.NOTIFICATION_FAILED, "communication-service");

    private final AuditEventRepository events;
    private final Clock clock;

    public AuditRecorder(AuditEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    public void record(String topic, EventEnvelope envelope) {
        // The consumer already skips redelivered events; this also covers the same event arriving on a replay
        if (events.existsByEventId(envelope.eventId())) {
            return;
        }
        JsonNode data = envelope.data();
        var row = new AuditEvent();
        row.setEventId(envelope.eventId());
        row.setTopic(topic);
        row.setEventType(envelope.eventType());
        row.setSourceService(SOURCE.get(topic));
        row.setAccountNo(text(data, "accountNo"));
        row.setActor(envelope.actor() != null ? envelope.actor() : "system:unknown");
        row.setCorrelationId(envelope.correlationId());
        row.setSummary(clean(summary(envelope.eventType(), data)));
        row.setOccurredAt(envelope.occurredAt() != null ? envelope.occurredAt() : clock.instant());
        row.setRecordedAt(clock.instant());
        events.save(row);
    }

    static String summary(String type, JsonNode d) {
        if (d == null) {
            return type;
        }
        return switch (type) {
            case "FileLoaded" -> "%s for %s: %d rows, %d loaded, %d rejected".formatted(
                text(d, "fileName"), text(d, "businessDate"), d.path("totalRows").asInt(), d.path("loadedRows").asInt(),
                d.path("rejectedRows").asInt());
            case "AccountUpgraded" -> "Upgraded %s -> %s for %s".formatted(
                text(d, "fromType"), text(d, "toType"), text(d, "businessDate"));
            case "UpgradeRejected" -> "Not upgraded (%s): %s".formatted(text(d, "currentType"), text(d, "reason"));
            case "UpgradeFailed" -> "Upgrade %s -> %s failed after %d attempt(s): %s".formatted(
                text(d, "fromType"), text(d, "toType"), d.path("attempts").asInt(), text(d, "reason"));
            case "NotificationSent" -> "Welcome email sent (attempt %d)".formatted(d.path("attempts").asInt());
            case "NotificationFailed" -> "Welcome email failed after %d attempt(s): %s".formatted(
                d.path("attempts").asInt(), text(d, "reason"));
            case "UserAction" -> "%s on %s %s".formatted(text(d, "action"), text(d, "targetType"), text(d, "targetId"));
            default -> type;
        };
    }

    /** No email addresses (a mail server's error can quote one), and within the column size. */
    static String clean(String summary) {
        String masked = EMAIL.matcher(summary).replaceAll("[email removed]");
        return masked.length() <= 1000 ? masked : masked.substring(0, 997) + "...";
    }

    private static String text(JsonNode data, String field) {
        JsonNode value = data == null ? null : data.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
