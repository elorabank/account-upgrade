package com.bank.upgrade.common.persistence.outbox;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Event written in the same transaction as the business change; a poller publishes it to Kafka. */
@Entity
@Table(name = "OUTBOX_EVENT")
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvent {

    public static final String NEW = "NEW";
    public static final String PUBLISHED = "PUBLISHED";

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "outbox_seq")
    @SequenceGenerator(name = "outbox_seq", sequenceName = "OUTBOX_EVENT_SEQ", allocationSize = 50)
    private Long id;

    @Column(name = "EVENT_ID", nullable = false, updatable = false, length = 36)
    private String eventId = UUID.randomUUID().toString();

    @Column(nullable = false, updatable = false, length = 100)
    private String topic;

    @Column(name = "MSG_KEY", updatable = false, length = 100)
    private String msgKey;

    @Column(name = "EVENT_TYPE", nullable = false, updatable = false, length = 100)
    private String eventType;

    @Column(name = "AGGREGATE_ID", updatable = false, length = 64)
    private String aggregateId;

    @Column(name = "CORRELATION_ID", updatable = false, length = 64)
    private String correlationId;

    @Lob
    @Column(nullable = false, updatable = false)
    private String payload;

    @Column(nullable = false, length = 12)
    private String status = NEW;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "PUBLISHED_AT")
    private Instant publishedAt;
}
