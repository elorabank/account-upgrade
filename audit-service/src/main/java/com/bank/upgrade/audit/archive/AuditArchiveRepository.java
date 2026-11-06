package com.bank.upgrade.audit.archive;

import com.bank.upgrade.common.retention.Partitions.Partition;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** What the archiver reads and records in the database: the trail's monthly partitions and the AUDIT_ARCHIVE rows. */
public interface AuditArchiveRepository {

    String WRITING = "WRITING";
    String ARCHIVED = "ARCHIVED";
    String DROPPED = "DROPPED";

    /** One AUDIT_ARCHIVE row. {@code month} is the month's first day (UTC). */
    record Entry(LocalDate month, String status, String fileName, Long rowCount, String sha256, Long firstId,
            Long lastId, Instant startedAt, Instant archivedAt, Instant droppedAt) {}

    /** One audit event as it is written to the archive: every column of AUDIT_EVENT. */
    record Row(long id, String eventId, String topic, String eventType, String sourceService, String accountNo,
            String actor, String correlationId, String summary, Instant occurredAt, Instant recordedAt) {}

    /** The trail's month partitions, oldest first; none when the table is not partitioned. */
    List<Partition> months();

    long rows(Partition month);

    /** Every row of the month, in id order, without holding them all in memory. */
    void forEachRow(Partition month, Consumer<Row> row);

    void drop(Partition month);

    Optional<Entry> entry(LocalDate month);

    /** Newest first. */
    List<Entry> entries();

    /** Marks the month WRITING for this copy; false when another copy (or an earlier run) has it already. */
    boolean claim(LocalDate month, Instant now);

    /** Takes over a month left WRITING since before {@code staleBefore}; false when it is not, or another copy won. */
    boolean takeOver(LocalDate month, Instant now, Instant staleBefore);

    /** Gives up a claim after a failure, so the next run starts the month again. */
    void release(LocalDate month);

    void archived(LocalDate month, String fileName, long rowCount, String sha256, Long firstId, Long lastId, Instant at);

    void dropped(LocalDate month, Instant at);
}
