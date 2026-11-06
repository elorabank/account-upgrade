package com.bank.upgrade.common.retention;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reads and drops the partitions of one of this schema's tables. The tables are partitioned by the services' own
 * migrations: by month (an interval of one month on a date) or one partition per value (automatic list), and the
 * database adds a partition whenever a new month or value arrives.
 * <p>
 * The first partition, made with the table, is never offered: it holds nothing (its range ends in 2020, or its only
 * value is 0), and a month-partitioned table cannot lose it. A table without partitions, for example on an Oracle
 * without the partitioning option, simply has none to offer.
 */
public final class Partitions {

    /** Oracle names: from the data dictionary or this code's own constants, but checked before going into DDL. */
    private static final Pattern NAME = Pattern.compile("[A-Z][A-Z0-9_$#]{0,127}");
    private static final Pattern DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern NUMBER = Pattern.compile("\\s*(-?\\d+)\\s*");

    /**
     * One partition and the bound of its key as Oracle writes it: for a month,
     * {@code TO_DATE(' 2026-01-01 00:00:00', ...)}, the first day after it; for a list value, the value itself.
     */
    public record Partition(String name, String highValue) {

        /** The first day after a month partition (rows in it are all before that day). */
        public Optional<LocalDate> endDate() {
            Matcher m = DATE.matcher(highValue == null ? "" : highValue);
            return m.find()
                ? Optional.of(LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))))
                : Optional.empty();
        }

        /** The one value of a list partition, for example the file id. */
        public OptionalLong value() {
            Matcher m = NUMBER.matcher(highValue == null ? "" : highValue);
            return m.matches() ? OptionalLong.of(Long.parseLong(m.group(1))) : OptionalLong.empty();
        }
    }

    private final JdbcTemplate jdbc;
    private final String table;

    public Partitions(JdbcTemplate jdbc, String table) {
        this.jdbc = jdbc;
        this.table = checked(table);
    }

    public String table() {
        return table;
    }

    /** The partitions that may be dropped, oldest first: all but the first. */
    public List<Partition> list() {
        // HIGH_VALUE is a LONG column: read as a string, which every Oracle version allows
        return jdbc.query("""
                SELECT PARTITION_NAME, HIGH_VALUE FROM USER_TAB_PARTITIONS
                WHERE TABLE_NAME = ? AND PARTITION_POSITION > 1
                ORDER BY PARTITION_POSITION""",
            (rs, i) -> new Partition(rs.getString(1), rs.getString(2)), table);
    }

    /** {@code T PARTITION ("P")}, to put after FROM. */
    public String from(Partition partition) {
        return table + " PARTITION (\"" + checked(partition.name()) + "\")";
    }

    public long rows(Partition partition) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + from(partition), Long.class);
        return n == null ? 0 : n;
    }

    /**
     * Drops the partition and its rows at once. The table's global indexes stay usable (Oracle marks the dropped
     * entries and cleans them up in the background). This is DDL: it commits on its own, so it runs outside any
     * transaction, and the audit table's insert-only trigger does not fire for it.
     */
    public void drop(Partition partition) {
        jdbc.execute("ALTER TABLE " + table + " DROP PARTITION \"" + checked(partition.name()) + "\" UPDATE GLOBAL INDEXES");
    }

    static String checked(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a plain Oracle name: " + name);
        }
        return name;
    }
}
