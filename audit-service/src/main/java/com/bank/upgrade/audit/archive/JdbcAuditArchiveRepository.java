package com.bank.upgrade.audit.archive;

import com.bank.upgrade.common.retention.Partitions;
import com.bank.upgrade.common.retention.Partitions.Partition;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link AuditArchiveRepository} on Oracle. Each statement commits on its own; nothing here needs more. */
public class JdbcAuditArchiveRepository implements AuditArchiveRepository {

    private static final String COLUMNS = """
        MONTH, STATUS, FILE_NAME, ROW_COUNT, SHA256, FIRST_ID, LAST_ID, STARTED_AT, ARCHIVED_AT, DROPPED_AT""";

    private final JdbcTemplate jdbc;
    private final JdbcTemplate reader;
    private final Partitions partitions;

    public JdbcAuditArchiveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        // Rows are streamed: fetched 1,000 at a time instead of Oracle's default of 10
        this.reader = new JdbcTemplate(jdbc.getDataSource());
        this.reader.setFetchSize(1000);
        this.partitions = new Partitions(jdbc, "AUDIT_EVENT");
    }

    @Override
    public List<Partition> months() {
        return partitions.list();
    }

    @Override
    public long rows(Partition month) {
        return partitions.rows(month);
    }

    @Override
    public void forEachRow(Partition month, Consumer<Row> row) {
        reader.query("""
            SELECT ID, EVENT_ID, TOPIC, EVENT_TYPE, SOURCE_SERVICE, ACCOUNT_NO, ACTOR, CORRELATION_ID, SUMMARY,
                   OCCURRED_AT, RECORDED_AT
            FROM %s ORDER BY ID""".formatted(partitions.from(month)), rs -> {
            row.accept(new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), instant(rs, 10), instant(rs, 11)));
        });
    }

    @Override
    public void drop(Partition month) {
        partitions.drop(month);
    }

    @Override
    public Optional<Entry> entry(LocalDate month) {
        return jdbc.query("SELECT " + COLUMNS + " FROM AUDIT_ARCHIVE WHERE MONTH = ?", JdbcAuditArchiveRepository::entry, month)
            .stream().findFirst();
    }

    @Override
    public List<Entry> entries() {
        return jdbc.query("SELECT " + COLUMNS + " FROM AUDIT_ARCHIVE ORDER BY MONTH DESC", JdbcAuditArchiveRepository::entry);
    }

    @Override
    public boolean claim(LocalDate month, Instant now) {
        try {
            jdbc.update("INSERT INTO AUDIT_ARCHIVE (MONTH, STATUS, STARTED_AT) VALUES (?, 'WRITING', ?)", month, utc(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public boolean takeOver(LocalDate month, Instant now, Instant staleBefore) {
        return jdbc.update("""
            UPDATE AUDIT_ARCHIVE SET STARTED_AT = ?
            WHERE MONTH = ? AND STATUS = 'WRITING' AND STARTED_AT < ?""", utc(now), month, utc(staleBefore)) == 1;
    }

    @Override
    public void release(LocalDate month) {
        jdbc.update("DELETE FROM AUDIT_ARCHIVE WHERE MONTH = ? AND STATUS = 'WRITING'", month);
    }

    @Override
    public void archived(LocalDate month, String fileName, long rowCount, String sha256, Long firstId, Long lastId, Instant at) {
        jdbc.update("""
            UPDATE AUDIT_ARCHIVE SET STATUS = 'ARCHIVED', FILE_NAME = ?, ROW_COUNT = ?, SHA256 = ?, FIRST_ID = ?,
                   LAST_ID = ?, ARCHIVED_AT = ?
            WHERE MONTH = ? AND STATUS = 'WRITING'""", fileName, rowCount, sha256, firstId, lastId, utc(at), month);
    }

    @Override
    public void dropped(LocalDate month, Instant at) {
        jdbc.update("UPDATE AUDIT_ARCHIVE SET STATUS = 'DROPPED', DROPPED_AT = ? WHERE MONTH = ? AND STATUS = 'ARCHIVED'",
            utc(at), month);
    }

    private static Entry entry(ResultSet rs, int i) throws SQLException {
        return new Entry(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getString(3), number(rs, 4),
            rs.getString(5), number(rs, 6), number(rs, 7), instant(rs, 8), instant(rs, 9), instant(rs, 10));
    }

    private static Long number(ResultSet rs, int column) throws SQLException {
        long n = rs.getLong(column);
        return rs.wasNull() ? null : n;
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
