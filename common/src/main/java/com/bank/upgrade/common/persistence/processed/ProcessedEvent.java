package com.bank.upgrade.common.persistence.processed;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Remembers consumed event ids so a redelivered Kafka message is not handled twice. */
@Entity
@Table(name = "PROCESSED_EVENT")
@Getter
@Setter
@NoArgsConstructor
public class ProcessedEvent {

    @Id
    @Column(name = "EVENT_ID", length = 36)
    private String eventId;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(name = "PROCESSED_AT", nullable = false)
    private Instant processedAt = Instant.now();

    public ProcessedEvent(String eventId, String topic) {
        this.eventId = eventId;
        this.topic = topic;
    }
}
