package com.bank.upgrade.common.retention;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What one service deletes when it is old enough, for example "processed-event records older than 30 days".
 * {@link RetentionRunner} calls {@link Purge#deleteBatch} again and again, each call in its own short transaction,
 * until a call deletes fewer rows than asked: many small deletes instead of one huge one.
 *
 * @param name        short name, used in logs, metrics and the {@code bank.retention.keep.<name>} setting
 * @param defaultKeep how long rows are kept unless {@code bank.retention.keep.<name>} says otherwise
 * @param purge       deletes at most {@code limit} rows older than {@code cutoff}, returns how many it deleted
 */
public record RetentionRule(String name, Duration defaultKeep, Purge purge) {

    @FunctionalInterface
    public interface Purge {
        int deleteBatch(Instant cutoff, int limit);
    }

    /**
     * A rule for a table whose age column is a TIMESTAMP. {@code deleteSql} takes the cutoff, then the limit:
     * {@code DELETE FROM T WHERE CREATED_AT < ? AND ROWNUM <= ?}.
     */
    public static RetentionRule olderThanTimestamp(String name, Duration defaultKeep, JdbcTemplate jdbc, String deleteSql) {
        return new RetentionRule(name, defaultKeep,
            (cutoff, limit) -> jdbc.update(deleteSql, cutoff.atOffset(ZoneOffset.UTC), limit));
    }

    /**
     * A rule for a table whose age column is a DATE (a business date): the cutoff is turned into a date in the
     * bank's time zone. {@code deleteSql} takes the cutoff date, then the limit.
     */
    public static RetentionRule olderThanDate(String name, Duration defaultKeep, JdbcTemplate jdbc, String deleteSql, ZoneId zone) {
        return new RetentionRule(name, defaultKeep,
            (cutoff, limit) -> jdbc.update(deleteSql, cutoff.atZone(zone).toLocalDate(), limit));
    }
}
