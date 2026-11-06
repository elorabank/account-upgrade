package com.bank.upgrade.audit.archive;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.upgrade.audit.archive.AuditArchiveRepository.Entry;
import com.bank.upgrade.audit.archive.AuditArchiveRepository.Row;
import com.bank.upgrade.audit.archive.AuditArchiver.Outcome;
import com.bank.upgrade.common.retention.Partitions.Partition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Moving old audit months to files, with the database faked in memory and a real folder for the files. */
class AuditArchiverTest {

    static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");

    /** The trail's month partitions and the AUDIT_ARCHIVE rows, in memory. */
    static class FakeDb implements AuditArchiveRepository {
        final Map<String, Partition> partitions = new LinkedHashMap<>();
        final Map<String, List<Row>> rows = new LinkedHashMap<>();
        final Map<LocalDate, Entry> entries = new TreeMap<>();
        final List<String> dropped = new ArrayList<>();
        /** Rows that arrive in a month while it is being written. */
        int lateRows;

        void month(String name, LocalDate firstDay, int events) {
            partitions.put(name, new Partition(name, "TO_DATE(' " + firstDay.plusMonths(1) + " 00:00:00', 'SYYYY-MM-DD HH24:MI:SS')"));
            List<Row> list = new ArrayList<>();
            for (int i = 1; i <= events; i++) {
                long id = firstDay.getMonthValue() * 1000L + i;
                list.add(new Row(id, "ev-" + id, "account.upgraded", "AccountUpgraded", "account-upgrade-service",
                    "AC" + id, "system:account-upgrade-service", "file-" + firstDay, "SAVINGS -> SAVINGS_PLUS",
                    firstDay.atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(i), firstDay.atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(i + 1)));
            }
            rows.put(name, list);
        }

        @Override public List<Partition> months() { return List.copyOf(partitions.values()); }
        @Override public long rows(Partition month) { return rows.get(month.name()).size() + lateRows; }
        @Override public void forEachRow(Partition month, Consumer<Row> row) { rows.get(month.name()).forEach(row); }
        @Override public void drop(Partition month) { partitions.remove(month.name()); dropped.add(month.name()); }
        @Override public Optional<Entry> entry(LocalDate month) { return Optional.ofNullable(entries.get(month)); }
        @Override public List<Entry> entries() { return List.copyOf(entries.values()).reversed(); }

        @Override
        public boolean claim(LocalDate month, Instant now) {
            return entries.putIfAbsent(month, new Entry(month, WRITING, null, null, null, null, null, now, null, null)) == null;
        }

        @Override
        public boolean takeOver(LocalDate month, Instant now, Instant staleBefore) {
            Entry e = entries.get(month);
            if (e == null || !e.status().equals(WRITING) || !e.startedAt().isBefore(staleBefore)) {
                return false;
            }
            entries.put(month, new Entry(month, WRITING, null, null, null, null, null, now, null, null));
            return true;
        }

        @Override public void release(LocalDate month) { entries.remove(month); }

        @Override
        public void archived(LocalDate month, String fileName, long rowCount, String sha256, Long firstId, Long lastId, Instant at) {
            Entry e = entries.get(month);
            entries.put(month, new Entry(month, ARCHIVED, fileName, rowCount, sha256, firstId, lastId, e.startedAt(), at, null));
        }

        @Override
        public void dropped(LocalDate month, Instant at) {
            Entry e = entries.get(month);
            entries.put(month, new Entry(month, DROPPED, e.fileName(), e.rowCount(), e.sha256(), e.firstId(), e.lastId(),
                e.startedAt(), e.archivedAt(), at));
        }
    }

    @TempDir Path dir;
    final FakeDb db = new FakeDb();
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private AuditArchiver archiver(ArchiveStore store) {
        return new AuditArchiver(db, store, new AuditArchiveProperties(true, null, Duration.ofDays(400), dir.toString(), null),
            Clock.fixed(NOW, ZoneOffset.UTC), meters);
    }

    private AuditArchiver archiver() {
        return archiver(new FileSystemArchiveStore(dir));
    }

    private static final LocalDate JAN_2025 = LocalDate.of(2025, 1, 1);

    @Test
    void aMonthPastTheKeepPeriodIsWrittenCheckedRecordedAndOnlyThenDropped() throws Exception {
        db.month("SYS_P1", JAN_2025, 3);
        db.month("SYS_P2", LocalDate.of(2025, 10, 1), 2);   // ended 31 Oct 2025, under 400 days ago: kept

        var results = archiver().archiveDue();

        assertThat(results).singleElement().satisfies(r -> {
            assertThat(r.month()).isEqualTo(JAN_2025);
            assertThat(r.outcome()).isEqualTo(Outcome.ARCHIVED);
            assertThat(r.rows()).isEqualTo(3);
        });
        Path file = dir.resolve("audit-events-2025-01.jsonl.gz");
        assertThat(file).exists();
        assertThat(db.dropped).containsExactly("SYS_P1");
        Entry entry = db.entries.get(JAN_2025);
        assertThat(entry.status()).isEqualTo(AuditArchiveRepository.DROPPED);
        assertThat(entry.fileName()).isEqualTo("audit-events-2025-01.jsonl.gz");
        assertThat(entry.rowCount()).isEqualTo(3);
        assertThat(entry.firstId()).isEqualTo(1001);
        assertThat(entry.lastId()).isEqualTo(1003);
        assertThat(entry.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        assertThat(meters.get("audit.archive.rows").counter().count()).isEqualTo(3);
        assertThat(meters.get("audit.archive.months").tag("outcome", "archived").counter().count()).isEqualTo(1);
    }

    @Test
    void theFileHasOneJsonEventPerLineInIdOrderWithEveryColumn() throws Exception {
        db.month("SYS_P1", JAN_2025, 2);

        archiver().archiveDue();

        List<JsonNode> events = new ArrayList<>();
        try (var lines = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(dir.resolve("audit-events-2025-01.jsonl.gz"))), StandardCharsets.UTF_8))) {
            for (String line; (line = lines.readLine()) != null; ) {
                events.add(new ObjectMapper().readTree(line));
            }
        }
        assertThat(events).extracting(e -> e.get("id").asLong()).containsExactly(1001L, 1002L);
        JsonNode first = events.getFirst();
        assertThat(first.get("eventId").asText()).isEqualTo("ev-1001");
        assertThat(first.get("eventType").asText()).isEqualTo("AccountUpgraded");
        assertThat(first.get("accountNo").asText()).isEqualTo("AC1001");
        assertThat(first.get("correlationId").asText()).isEqualTo("file-2025-01-01");
        assertThat(first.get("occurredAt").asText()).isEqualTo("2025-01-01T00:00:01Z");
        assertThat(first.fieldNames()).toIterable().contains("actor", "summary", "topic", "sourceService", "recordedAt");
    }

    @Test
    void aMonthAnotherCopyIsWritingIsLeftToIt() {
        db.month("SYS_P1", JAN_2025, 3);
        db.entries.put(JAN_2025, new Entry(JAN_2025, AuditArchiveRepository.WRITING, null, null, null, null, null,
            NOW.minus(Duration.ofMinutes(5)), null, null));

        var results = archiver().archiveDue();

        assertThat(results).extracting(AuditArchiver.Result::outcome).containsExactly(Outcome.BUSY);
        assertThat(db.dropped).isEmpty();
        assertThat(dir.resolve("audit-events-2025-01.jsonl.gz")).doesNotExist();
    }

    @Test
    void aMonthLeftHalfWrittenByACopyThatStoppedIsTakenOver() {
        db.month("SYS_P1", JAN_2025, 3);
        db.entries.put(JAN_2025, new Entry(JAN_2025, AuditArchiveRepository.WRITING, null, null, null, null, null,
            NOW.minus(Duration.ofHours(3)), null, null));

        assertThat(archiver().archiveDue()).extracting(AuditArchiver.Result::outcome).containsExactly(Outcome.ARCHIVED);
        assertThat(db.dropped).containsExactly("SYS_P1");
    }

    @Test
    void aStoredFileThatDoesNotMatchWhatWasWrittenDropsNothingAndTheNextRunStartsAgain() {
        db.month("SYS_P1", JAN_2025, 3);
        // A store that keeps only half of each file, like a full disk or a broken upload
        ArchiveStore lossy = new FileSystemArchiveStore(dir) {
            @Override
            public void put(String name, Path file) throws IOException {
                byte[] all = Files.readAllBytes(file);
                Path half = Files.createTempFile(dir, "half", ".gz");
                Files.write(half, java.util.Arrays.copyOf(all, all.length / 2));
                super.put(name, half);
            }
        };

        var results = archiver(lossy).archiveDue();

        assertThat(results).extracting(AuditArchiver.Result::outcome).containsExactly(Outcome.FAILED);
        assertThat(db.dropped).isEmpty();
        assertThat(db.entries).as("the claim is given up, so the next run starts the month again").isEmpty();

        assertThat(archiver().archiveDue()).extracting(AuditArchiver.Result::outcome).containsExactly(Outcome.ARCHIVED);
    }

    @Test
    void rowsThatArriveWhileTheMonthIsWrittenKeepItInTheDatabase() {
        db.month("SYS_P1", JAN_2025, 3);
        db.lateRows = 1;

        var results = archiver().archiveDue();

        assertThat(results).extracting(AuditArchiver.Result::outcome).containsExactly(Outcome.CHANGED);
        assertThat(results.getFirst().detail()).contains("4 rows in the table but 3");
        assertThat(db.dropped).isEmpty();
        assertThat(db.entries.get(JAN_2025).status()).isEqualTo(AuditArchiveRepository.ARCHIVED);
    }

    @Test
    void aMonthArchivedEarlierButNotDroppedIsDroppedOnTheNextRun() {
        db.month("SYS_P1", JAN_2025, 3);
        db.entries.put(JAN_2025, new Entry(JAN_2025, AuditArchiveRepository.ARCHIVED, "audit-events-2025-01.jsonl.gz",
            3L, "abc", 1001L, 1003L, NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofDays(1)), null));

        assertThat(archiver().archiveDue()).extracting(AuditArchiver.Result::outcome).containsExactly(Outcome.ARCHIVED);
        assertThat(db.dropped).containsExactly("SYS_P1");
        assertThat(db.entries.get(JAN_2025).status()).isEqualTo(AuditArchiveRepository.DROPPED);
    }

    @Test
    void eventsForAMonthAlreadyMovedToAFileAreReportedNotTouched() {
        db.month("SYS_P9", JAN_2025, 1);   // a replayed old event brought the month's partition back
        db.entries.put(JAN_2025, new Entry(JAN_2025, AuditArchiveRepository.DROPPED, "audit-events-2025-01.jsonl.gz",
            3L, "abc", 1001L, 1003L, NOW, NOW, NOW));

        var results = archiver().archiveDue();

        assertThat(results).extracting(AuditArchiver.Result::outcome).containsExactly(Outcome.CHANGED);
        assertThat(db.dropped).isEmpty();
    }

    @Test
    void aTableWithoutPartitionsKeepsTheWholeTrail() {
        assertThat(archiver().archiveDue()).isEmpty();
    }

    @Test
    void theStoreOnlyAcceptsPlainFileNames() {
        var store = new FileSystemArchiveStore(dir);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.open("../secrets"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
