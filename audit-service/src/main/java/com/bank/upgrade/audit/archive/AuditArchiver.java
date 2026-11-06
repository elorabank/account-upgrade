package com.bank.upgrade.audit.archive;

import com.bank.upgrade.audit.archive.AuditArchiveRepository.Entry;
import com.bank.upgrade.audit.archive.AuditArchiveRepository.Row;
import com.bank.upgrade.common.retention.Partitions.Partition;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Moves months of the audit trail that ended more than {@code keep} ago (default 400 days) out of the database,
 * into one compressed file per month: JSON lines, one event per line, in id order
 * ({@code audit-events-2025-01.jsonl.gz}). The trail is still kept forever; old months are just no longer
 * searchable in the console.
 * <p>
 * For each month: claim it in AUDIT_ARCHIVE (so two copies of the service never write the same month), write the
 * file, store it, read the stored copy back and check it has every row of the month and the same SHA-256, record
 * the file (ARCHIVED), and only then drop the month's partition (DROPPED). Any step that fails leaves the rows in
 * the database and the next run starts again; a month whose rows changed after it was written (an event replayed
 * from long ago) is reported and left alone for a person to look at.
 * <p>
 * Metrics: {@code audit.archive.months} by result, {@code audit.archive.rows}.
 */
public class AuditArchiver {

    private static final Logger log = LoggerFactory.getLogger(AuditArchiver.class);
    private static final JsonFactory JSON = new JsonFactory();

    public enum Outcome {
        /** Written, checked and dropped from the database. */
        ARCHIVED,
        /** Another copy of the service is on it. */
        BUSY,
        /** Rows arrived after the month was archived; nothing was dropped. A person should look. */
        CHANGED,
        /** Something failed; the rows are still in the database and the next run tries again. */
        FAILED
    }

    public record Result(LocalDate month, Outcome outcome, long rows, String detail) {}

    private record Written(long rows, String sha256, Long firstId, Long lastId) {}

    private final AuditArchiveRepository db;
    private final ArchiveStore store;
    private final AuditArchiveProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;

    public AuditArchiver(AuditArchiveRepository db, ArchiveStore store, AuditArchiveProperties properties, Clock clock,
            MeterRegistry meters) {
        this.db = db;
        this.store = store;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
    }

    @Scheduled(cron = "${bank.audit.archive.cron:0 0 5 * * *}", zone = "${bank.time-zone}")
    public void scheduled() {
        archiveDue();
    }

    public List<Result> archiveDue() {
        LocalDate lastDay = clock.instant().minus(properties.keep()).atZone(ZoneOffset.UTC).toLocalDate();
        List<Result> results = new ArrayList<>();
        for (Partition partition : db.months()) {
            Optional<LocalDate> end = partition.endDate();
            if (end.isEmpty()) {
                continue;
            }
            if (end.get().isAfter(lastDay)) {
                break;   // oldest first: the rest are newer still
            }
            Result result = archive(partition, end.get().minusMonths(1));
            results.add(result);
            Counter.builder("audit.archive.months").description("Audit months handled by the archive job, by outcome")
                .tag("outcome", result.outcome().name().toLowerCase()).register(meters).increment();
        }
        return results;
    }

    /** Lists what was archived, newest month first. */
    public List<Entry> entries() {
        return db.entries();
    }

    private Result archive(Partition partition, LocalDate month) {
        Instant now = clock.instant();
        Optional<Entry> entry = db.entry(month);
        if (entry.isEmpty()) {
            if (!db.claim(month, now)) {
                return new Result(month, Outcome.BUSY, 0, "claimed by another copy");
            }
        } else {
            switch (entry.get().status()) {
                case AuditArchiveRepository.WRITING -> {
                    if (!db.takeOver(month, now, now.minus(properties.staleAfter()))) {
                        return new Result(month, Outcome.BUSY, 0, "being written since " + entry.get().startedAt());
                    }
                    log.warn("Audit archive {}: taking over, the copy writing it since {} stopped", month, entry.get().startedAt());
                }
                case AuditArchiveRepository.ARCHIVED -> {
                    // Written and checked before, but the drop did not happen: drop it now if nothing changed since
                    return dropIfUnchanged(partition, month, entry.get().rowCount(), entry.get().fileName());
                }
                default -> {
                    return changed(month, "rows for this month arrived after it was archived to "
                        + entry.get().fileName() + " and its partition dropped; they are still in the table");
                }
            }
        }

        String name = "audit-events-" + month.toString().substring(0, 7) + ".jsonl.gz";
        Written written;
        try {
            written = writeAndStore(partition, name);
        } catch (IOException | RuntimeException e) {
            db.release(month);
            log.error("Audit archive {}: not written, the rows stay in the database and the next run tries again", month, e);
            return new Result(month, Outcome.FAILED, 0, e.toString());
        }
        db.archived(month, name, written.rows(), written.sha256(), written.firstId(), written.lastId(), clock.instant());
        log.info("Audit archive {}: {} events written to {} (SHA-256 {})", month, written.rows(), store.location(name),
            written.sha256());
        return dropIfUnchanged(partition, month, written.rows(), name);
    }

    private Written writeAndStore(Partition partition, String name) throws IOException {
        Path temp = Files.createTempFile("audit-archive-", ".jsonl.gz");
        try {
            Written written = write(partition, temp);
            store.put(name, temp);
            Written stored = readBack(name);
            if (stored.rows() != written.rows() || !stored.sha256().equals(written.sha256())) {
                throw new IOException("The stored " + name + " does not match what was written: " + stored.rows()
                    + " rows, SHA-256 " + stored.sha256() + "; expected " + written.rows() + ", " + written.sha256());
            }
            return written;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private Result dropIfUnchanged(Partition partition, LocalDate month, Long archivedRows, String name) {
        long now = db.rows(partition);
        if (archivedRows == null || now != archivedRows) {
            return changed(month, "the month has " + now + " rows in the table but " + archivedRows + " in " + name
                + "; nothing was dropped");
        }
        try {
            db.drop(partition);
        } catch (RuntimeException e) {
            log.error("Audit archive {}: archived to {}, but its partition was not dropped; the next run tries again", month, name, e);
            return new Result(month, Outcome.FAILED, now, e.toString());
        }
        db.dropped(month, clock.instant());
        Counter.builder("audit.archive.rows").description("Audit events moved from the database to archive files")
            .register(meters).increment(now);
        log.info("Audit archive {}: partition dropped, {} is now the only copy of its {} events", month, name, now);
        return new Result(month, Outcome.ARCHIVED, now, store.location(name));
    }

    private Result changed(LocalDate month, String detail) {
        log.error("Audit archive {}: {}", month, detail);
        return new Result(month, Outcome.CHANGED, 0, detail);
    }

    /** Writes the month as gzip-compressed JSON lines; returns the count, the file's SHA-256 and the id range. */
    private Written write(Partition partition, Path file) throws IOException {
        MessageDigest sha = sha256();
        long[] rows = {0};
        Long[] ids = {null, null};
        try (OutputStream out = new GZIPOutputStream(new DigestOutputStream(Files.newOutputStream(file), sha), 64 * 1024)) {
            db.forEachRow(partition, row -> {
                try {
                    writeLine(out, row);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                rows[0]++;
                if (ids[0] == null) {
                    ids[0] = row.id();
                }
                ids[1] = row.id();
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return new Written(rows[0], HexFormat.of().formatHex(sha.digest()), ids[0], ids[1]);
    }

    private static void writeLine(OutputStream out, Row row) throws IOException {
        // A new generator per line, not closing the stream: each event is one self-contained line of JSON
        try (JsonGenerator json = JSON.createGenerator(out).disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)) {
            json.writeStartObject();
            json.writeNumberField("id", row.id());
            json.writeStringField("eventId", row.eventId());
            json.writeStringField("topic", row.topic());
            json.writeStringField("eventType", row.eventType());
            json.writeStringField("sourceService", row.sourceService());
            json.writeStringField("accountNo", row.accountNo());
            json.writeStringField("actor", row.actor());
            json.writeStringField("correlationId", row.correlationId());
            json.writeStringField("summary", row.summary());
            json.writeStringField("occurredAt", row.occurredAt() == null ? null : row.occurredAt().toString());
            json.writeStringField("recordedAt", row.recordedAt() == null ? null : row.recordedAt().toString());
            json.writeEndObject();
        }
        out.write('\n');
    }

    /** Reads the stored file back: its SHA-256 and how many events it holds, each line parsed as JSON. */
    private Written readBack(String name) throws IOException {
        MessageDigest sha = sha256();
        long rows = 0;
        try (InputStream raw = new DigestInputStream(store.open(name), sha);
                var lines = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                try (var parser = JSON.createParser(line)) {
                    while (parser.nextToken() != null) {
                        // parsing the whole line is the check
                    }
                }
                rows++;
            }
            raw.transferTo(OutputStream.nullOutputStream());   // anything after the gzip data still counts in the hash
        }
        return new Written(rows, HexFormat.of().formatHex(sha.digest()), null, null);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
