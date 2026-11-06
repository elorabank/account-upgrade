package com.bank.upgrade.common.retention;

import com.bank.upgrade.common.retention.Partitions.Partition;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The fast part of a {@link RetentionRule}: drops whole partitions whose rows are all past the cutoff, instead of
 * deleting the rows one by one. Dropping a month of a million rows takes a moment and leaves no undo behind.
 * <p>
 * A partition is dropped only when no row in it must stay ({@code keepIf}, for example a letter still PENDING);
 * otherwise the rule's row deletes take what they may and the partition goes once it holds nothing to keep.
 * The check and the drop are two steps: a row that changed in between, in a partition this old, would be lost,
 * and nothing writes there.
 * <p>
 * Without partitions (an Oracle without the partitioning option) there is nothing to drop and the rule's row
 * deletes do all the work, as before.
 */
public class PartitionDrop {

    private static final Logger log = LoggerFactory.getLogger(PartitionDrop.class);

    /** What one run dropped. {@code failed} counts partitions that could not be dropped (they are tried again next run). */
    public record Dropped(int partitions, long rows, int failed) {
        static final Dropped NONE = new Dropped(0, 0, 0);
    }

    private final JdbcTemplate jdbc;
    private final Partitions partitions;
    private final ZoneId monthZone;
    private final String keepIf;

    private PartitionDrop(JdbcTemplate jdbc, String table, ZoneId monthZone, String keepIf) {
        this.jdbc = jdbc;
        this.partitions = new Partitions(jdbc, table);
        this.monthZone = monthZone;
        this.keepIf = keepIf;
    }

    /**
     * For a table partitioned by month: a month is dropped once it ended on or before the cutoff's day.
     *
     * @param monthZone the zone of the partition key's dates: the bank's zone for a business date, UTC for a
     *                  day derived from a timestamp
     * @param keepIf    a row that keeps its partition, as SQL, for example {@code STATUS = 'PENDING'}
     */
    public static PartitionDrop byMonth(JdbcTemplate jdbc, String table, ZoneId monthZone, String keepIf) {
        return new PartitionDrop(jdbc, table, monthZone, keepIf);
    }

    /**
     * For a table with one partition per value (for example per file): a partition is dropped once none of its rows
     * must stay. {@code keepIf} may use the cutoff as one {@code ?} parameter, for example
     * {@code FILE_ID NOT IN (SELECT ID FROM FILE_INGESTION_LOG WHERE COMPLETED_AT < ?)}.
     */
    public static PartitionDrop byValue(JdbcTemplate jdbc, String table, String keepIf) {
        return new PartitionDrop(jdbc, table, null, keepIf);
    }

    public String table() {
        return partitions.table();
    }

    public Dropped dropOlderThan(Instant cutoff) {
        int dropped = 0;
        int failed = 0;
        long rows = 0;
        for (Partition partition : partitions.list()) {
            if (monthZone != null) {
                Optional<LocalDate> end = partition.endDate();
                if (end.isEmpty()) {
                    continue;
                }
                if (end.get().isAfter(cutoff.atZone(monthZone).toLocalDate())) {
                    break;   // oldest first: every later month is newer still
                }
            }
            try {
                if (anyToKeep(partition, cutoff)) {
                    continue;
                }
                long n = partitions.rows(partition);
                partitions.drop(partition);
                dropped++;
                rows += n;
                log.info("Retention: dropped partition {} of {} ({}) with {} rows", partition.name(), table(),
                    partition.highValue(), n);
            } catch (DataAccessException e) {
                // For example another copy dropped it a moment ago, or a long query still holds it
                failed++;
                log.warn("Retention: partition {} of {} not dropped, next run tries again: {}", partition.name(),
                    table(), e.getMostSpecificCause().getMessage());
            }
        }
        return dropped == 0 && failed == 0 ? Dropped.NONE : new Dropped(dropped, rows, failed);
    }

    private boolean anyToKeep(Partition partition, Instant cutoff) {
        String sql = "SELECT COUNT(*) FROM " + partitions.from(partition) + " WHERE (" + keepIf + ") AND ROWNUM = 1";
        Long n = keepIf.contains("?")
            ? jdbc.queryForObject(sql, Long.class, cutoff.atOffset(ZoneOffset.UTC))
            : jdbc.queryForObject(sql, Long.class);
        return n != null && n > 0;
    }
}
