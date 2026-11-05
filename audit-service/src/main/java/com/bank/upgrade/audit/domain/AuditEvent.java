package com.bank.upgrade.audit.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Insert-only: the database also blocks UPDATE and DELETE with a trigger. Because a row can never be changed or
 * erased, it holds no customer names, emails or other personal data: only ids, the action and a short summary.
 */
@Entity
@Table(name = "AUDIT_EVENT")
@Getter
@Setter
@NoArgsConstructor
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "audit_event_seq")
    @SequenceGenerator(name = "audit_event_seq", sequenceName = "AUDIT_EVENT_SEQ", allocationSize = 50)
    private Long id;

    @Column(name = "EVENT_ID", nullable = false, updatable = false, length = 36)
    private String eventId;

    @Column(nullable = false, updatable = false, length = 100)
    private String topic;

    @Column(name = "EVENT_TYPE", nullable = false, updatable = false, length = 100)
    private String eventType;

    @Column(name = "SOURCE_SERVICE", updatable = false, length = 60)
    private String sourceService;

    @Column(name = "ACCOUNT_NO", updatable = false, length = 34)
    private String accountNo;

    /** User email/subject for manual actions, or system:<scheduler> for automatic ones. */
    @Column(nullable = false, updatable = false, length = 254)
    private String actor;

    @Column(name = "CORRELATION_ID", updatable = false, length = 64)
    private String correlationId;

    /** For example "SAVINGS -> SAVINGS_PLUS" or a rejection reason. Never personal data. */
    @Column(updatable = false, length = 1000)
    private String summary;

    @Column(name = "OCCURRED_AT", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "RECORDED_AT", nullable = false, updatable = false)
    private Instant recordedAt = Instant.now();
}
