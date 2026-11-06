package com.bank.upgrade.common.retention;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code bank.retention}.
 *
 * @param enabled   switch the daily clean-up off (it is on by default)
 * @param cron      when it runs, in {@code bank.time-zone}; by default 05:30, after the nightly load, upgrade and emails
 * @param batchSize rows deleted per transaction
 * @param keep      how long each rule keeps rows, by rule name, overriding its default
 *                  (for example {@code bank.retention.keep.processed-events=14d})
 */
@ConfigurationProperties("bank.retention")
public record RetentionProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("0 30 5 * * *") String cron,
        @DefaultValue("10000") int batchSize,
        Map<String, Duration> keep) {

    public RetentionProperties {
        keep = keep == null ? Map.of() : Map.copyOf(keep);
        if (batchSize < 1) {
            throw new IllegalArgumentException("bank.retention.batch-size must be at least 1");
        }
    }

    public Duration keepFor(RetentionRule rule) {
        return keep.getOrDefault(rule.name(), rule.defaultKeep());
    }
}
