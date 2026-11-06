package com.bank.upgrade.common.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.upgrade.common.retention.Partitions.Partition;
import com.bank.upgrade.test.NoOpTransactionManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Dropping whole partitions, against a fake of the few statements Oracle is sent (no database). */
class PartitionDropTest {

    static final ZoneId BANK = ZoneId.of("Asia/Kolkata");

    /** A partitioned table as the statements see it: partitions, their rows, and the rows that must stay. */
    static class FakeOracle extends JdbcTemplate {
        record Part(String highValue, long rows, long toKeep) {}

        final Map<String, Part> parts = new LinkedHashMap<>();
        final List<String> dropped = new ArrayList<>();
        final List<Object> keepArgs = new ArrayList<>();
        String failDrop;

        FakeOracle month(String name, String firstDayAfter, long rows, long toKeep) {
            parts.put(name, new Part("TO_DATE(' " + firstDayAfter + " 00:00:00', 'SYYYY-MM-DD HH24:MI:SS', 'NLS_CALENDAR=GREGORIAN')", rows, toKeep));
            return this;
        }

        FakeOracle value(String name, long value, long rows, long toKeep) {
            parts.put(name, new Part(String.valueOf(value), rows, toKeep));
            return this;
        }

        private static final Pattern FROM = Pattern.compile("FROM \\w+ PARTITION \\(\"(\\w+)\"\\)");

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            assertThat(sql).contains("USER_TAB_PARTITIONS").contains("PARTITION_POSITION > 1");
            List<T> list = new ArrayList<>();
            parts.forEach((name, p) -> list.add((T) new Partition(name, p.highValue())));
            return list;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            Matcher m = FROM.matcher(sql);
            assertThat(m.find()).as(sql).isTrue();
            Part p = parts.get(m.group(1));
            if (sql.contains("WHERE (")) {
                keepArgs.addAll(List.of(args));
                return (T) Long.valueOf(p.toKeep());
            }
            return (T) Long.valueOf(p.rows());
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> type) {
            return queryForObject(sql, type, new Object[0]);
        }

        @Override
        public void execute(String sql) {
            Matcher m = Pattern.compile("ALTER TABLE \\w+ DROP PARTITION \"(\\w+)\" UPDATE GLOBAL INDEXES").matcher(sql);
            assertThat(m.matches()).as(sql).isTrue();
            if (m.group(1).equals(failDrop)) {
                throw new DataAccessResourceFailureException("ORA-00054: resource busy");
            }
            parts.remove(m.group(1));
            dropped.add(m.group(1));
        }
    }

    final FakeOracle oracle = new FakeOracle();

    @Test
    void monthsThatEndedByTheCutoffAndHoldNothingToKeepAreDroppedWhole() {
        oracle.month("SYS_P1", "2025-01-01", 300, 0)
            .month("SYS_P2", "2025-02-01", 200, 1)      // one request still PENDING
            .month("SYS_P3", "2025-03-01", 100, 0)
            .month("SYS_P4", "2025-04-01", 50, 0);      // ends after the cutoff day
        var drop = PartitionDrop.byMonth(oracle, "UPGRADE_REQUEST", BANK, "STATUS NOT IN ('UPGRADED')");

        var result = drop.dropOlderThan(Instant.parse("2025-03-15T00:00:00Z"));

        assertThat(oracle.dropped).containsExactly("SYS_P1", "SYS_P3");
        assertThat(result).isEqualTo(new PartitionDrop.Dropped(2, 400, 0));
    }

    @Test
    void aMonthEndingOnTheCutoffDayInTheKeysZoneIsOldEnough() {
        oracle.month("SYS_P1", "2025-03-01", 10, 0);
        // 28 Feb 20:00 UTC is already 1 March in India
        Instant cutoff = Instant.parse("2025-02-28T20:00:00Z");

        PartitionDrop.byMonth(oracle, "UPGRADE_REQUEST", ZoneOffset.UTC, "1 = 0").dropOlderThan(cutoff);
        assertThat(oracle.dropped).isEmpty();

        PartitionDrop.byMonth(oracle, "UPGRADE_REQUEST", BANK, "1 = 0").dropOlderThan(cutoff);
        assertThat(oracle.dropped).containsExactly("SYS_P1");
    }

    @Test
    void perValuePartitionsAreDroppedWhenNoRowMustStayAndTheCheckGetsTheCutoff() {
        oracle.value("SYS_P7", 51, 2000, 0).value("SYS_P8", 101, 1800, 1800);   // file 101 is newer than the cutoff
        Instant cutoff = Instant.parse("2026-10-07T00:00:00Z");

        var result = PartitionDrop.byValue(oracle, "ACCOUNT_STAGING",
            "FILE_ID NOT IN (SELECT ID FROM FILE_INGESTION_LOG WHERE COMPLETED_AT < ?)").dropOlderThan(cutoff);

        assertThat(oracle.dropped).containsExactly("SYS_P7");
        assertThat(result.rows()).isEqualTo(2000);
        assertThat(oracle.keepArgs).containsOnly(OffsetDateTime.parse("2026-10-07T00:00:00Z"));
    }

    @Test
    void aPartitionThatCannotBeDroppedIsCountedAndTheOthersStillGo() {
        oracle.month("SYS_P1", "2025-01-01", 5, 0).month("SYS_P2", "2025-02-01", 6, 0);
        oracle.failDrop = "SYS_P1";

        var result = PartitionDrop.byMonth(oracle, "WELCOME_LETTER", ZoneOffset.UTC, "STATUS = 'PENDING'")
            .dropOlderThan(Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(oracle.dropped).containsExactly("SYS_P2");
        assertThat(result).isEqualTo(new PartitionDrop.Dropped(1, 6, 1));
    }

    @Test
    void aTableWithoutPartitionsHasNothingToDrop() {
        var result = PartitionDrop.byMonth(oracle, "WELCOME_LETTER", ZoneOffset.UTC, "STATUS = 'PENDING'")
            .dropOlderThan(Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(result).isEqualTo(new PartitionDrop.Dropped(0, 0, 0));
    }

    @Test
    void theRunnerDropsPartitionsFirstThenDeletesTheRowsLeftAndCountsBoth() {
        oracle.month("SYS_P1", "2025-01-01", 300, 0);
        var meters = new SimpleMeterRegistry();
        List<Integer> batches = new ArrayList<>();
        var rule = new RetentionRule("letters", Duration.ofDays(400), (cutoff, limit) -> {
            assertThat(oracle.dropped).as("partitions go before the row deletes").containsExactly("SYS_P1");
            batches.add(limit);
            return 7;
        }).withPartitions(PartitionDrop.byMonth(oracle, "WELCOME_LETTER", ZoneOffset.UTC, "STATUS = 'PENDING'"));
        var runner = new RetentionRunner(List.of(rule), new RetentionProperties(true, null, 100, Map.of()),
            new NoOpTransactionManager(), Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC), meters);

        var result = runner.runAll().getFirst();

        assertThat(result.deleted()).isEqualTo(307);
        assertThat(result.partitionsDropped()).isEqualTo(1);
        assertThat(result.error()).isNull();
        assertThat(batches).hasSize(1);
        assertThat(meters.get("bank.retention.partitions.dropped").tag("rule", "letters").counter().count()).isEqualTo(1);
        assertThat(meters.get("bank.retention.deleted").tag("rule", "letters").counter().count()).isEqualTo(307);
    }

    @Test
    void whenPartitionsCannotBeReadTheRowDeletesStillRun() {
        var broken = new FakeOracle() {
            @Override
            public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
                throw new DataAccessResourceFailureException("ORA-00942: table or view does not exist");
            }
        };
        var rule = new RetentionRule("letters", Duration.ofDays(400), (cutoff, limit) -> 3)
            .withPartitions(PartitionDrop.byMonth(broken, "WELCOME_LETTER", ZoneOffset.UTC, "STATUS = 'PENDING'"));
        var runner = new RetentionRunner(List.of(rule), new RetentionProperties(true, null, 100, Map.of()),
            new NoOpTransactionManager(), Clock.systemUTC(), new SimpleMeterRegistry());

        var result = runner.runAll().getFirst();

        assertThat(result.deleted()).isEqualTo(3);
        assertThat(result.error()).contains("not read").contains("ORA-00942");
    }

    // --- reading Oracle's partition bounds ---------------------------------------------------------------------

    @Test
    void theBoundOfAMonthAndOfAValueAreRead() {
        var month = new Partition("SYS_P1", "TO_DATE(' 2026-01-01 00:00:00', 'SYYYY-MM-DD HH24:MI:SS', 'NLS_CALENDAR=GREGORIAN')");
        var file = new Partition("SYS_P2", "51");

        assertThat(month.endDate()).contains(LocalDate.of(2026, 1, 1));
        assertThat(month.value()).isEmpty();
        assertThat(file.value()).hasValue(51);
        assertThat(file.endDate()).isEmpty();
    }

    @Test
    void onlyPlainOracleNamesGoIntoStatements() {
        assertThat(Partitions.checked("SYS_P123")).isEqualTo("SYS_P123");
        assertThatThrownBy(() -> new Partitions(oracle, "T; DROP TABLE X")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Partitions(oracle, "T").drop(new Partition("P\" X", "1")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
