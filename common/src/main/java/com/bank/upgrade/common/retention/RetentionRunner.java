package com.bank.upgrade.common.retention;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The daily clean-up: for every {@link RetentionRule} of this service, deletes the rows older than the rule keeps
 * them, a batch at a time, each batch in its own transaction (a delete of millions of rows in one transaction
 * would fill Oracle's undo and lock the table for long). A rule that fails is logged and the others still run.
 * <p>
 * Several copies of a service may run it at the same time; that is safe (a row is deleted once) but not faster.
 * Metric: {@code bank.retention.deleted}, rows deleted by rule.
 */
public class RetentionRunner {

    private static final Logger log = LoggerFactory.getLogger(RetentionRunner.class);
    /** A guard against a rule that never finishes: 10,000 batches of the default size is 100 million rows. */
    private static final int MAX_BATCHES = 10_000;

    public record Result(String rule, Instant cutoff, long deleted, String error) {}

    private final List<RetentionRule> rules;
    private final RetentionProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry meters;

    public RetentionRunner(List<RetentionRule> rules, RetentionProperties properties,
            PlatformTransactionManager transactionManager, Clock clock, MeterRegistry meters) {
        this.rules = List.copyOf(rules);
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.meters = meters;
    }

    @Scheduled(cron = "${bank.retention.cron:0 30 5 * * *}", zone = "${bank.time-zone}")
    public void scheduled() {
        runAll();
    }

    public List<Result> runAll() {
        List<Result> results = new ArrayList<>();
        for (RetentionRule rule : rules) {
            results.add(run(rule));
        }
        return results;
    }

    private Result run(RetentionRule rule) {
        Instant cutoff = clock.instant().minus(properties.keepFor(rule));
        int limit = properties.batchSize();
        long total = 0;
        try {
            for (int batch = 0; batch < MAX_BATCHES; batch++) {
                Integer deleted = tx.execute(status -> rule.purge().deleteBatch(cutoff, limit));
                int n = deleted == null ? 0 : deleted;
                total += n;
                if (n < limit) {
                    break;
                }
            }
            log.info("Retention {}: deleted {} rows older than {}", rule.name(), total, cutoff);
            return new Result(rule.name(), cutoff, total, null);
        } catch (RuntimeException e) {
            log.error("Retention {} failed after deleting {} rows; the next run continues", rule.name(), total, e);
            return new Result(rule.name(), cutoff, total, e.toString());
        } finally {
            if (total > 0) {
                Counter.builder("bank.retention.deleted").description("Rows deleted by the daily clean-up")
                    .tag("rule", rule.name()).register(meters).increment(total);
            }
        }
    }
}
