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
 * The daily clean-up: for every {@link RetentionRule} of this service, first drops the partitions whose rows are
 * all older than the rule keeps them, then deletes the old rows that are left, a batch at a time, each batch in its
 * own transaction (a delete of millions of rows in one transaction would fill Oracle's undo and lock the table for
 * long). A rule that fails is logged and the others still run.
 * <p>
 * Several copies of a service may run it at the same time; that is safe (a row is deleted once) but not faster.
 * Metrics: {@code bank.retention.deleted}, rows removed by rule (dropped partitions included), and
 * {@code bank.retention.partitions.dropped}.
 */
public class RetentionRunner {

    private static final Logger log = LoggerFactory.getLogger(RetentionRunner.class);
    /** A guard against a rule that never finishes: 10,000 batches of the default size is 100 million rows. */
    private static final int MAX_BATCHES = 10_000;

    /** @param deleted rows removed, by dropped partitions and by row deletes together */
    public record Result(String rule, Instant cutoff, long deleted, int partitionsDropped, String error) {}

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
        int partitionsDropped = 0;
        String partitionProblem = null;
        try {
            if (rule.partitions() != null) {
                // DDL commits by itself, so this runs outside a transaction; a failure here leaves the row deletes to it
                try {
                    PartitionDrop.Dropped dropped = rule.partitions().dropOlderThan(cutoff);
                    partitionsDropped = dropped.partitions();
                    total += dropped.rows();
                    if (dropped.failed() > 0) {
                        partitionProblem = dropped.failed() + " partition(s) of " + rule.partitions().table() + " not dropped";
                    }
                } catch (RuntimeException e) {
                    partitionProblem = "partitions of " + rule.partitions().table() + " not read: " + e;
                    log.warn("Retention {}: {}; deleting rows instead", rule.name(), partitionProblem);
                }
            }
            for (int batch = 0; batch < MAX_BATCHES; batch++) {
                Integer deleted = tx.execute(status -> rule.purge().deleteBatch(cutoff, limit));
                int n = deleted == null ? 0 : deleted;
                total += n;
                if (n < limit) {
                    break;
                }
            }
            log.info("Retention {}: removed {} rows older than {} ({} whole partitions)", rule.name(), total, cutoff,
                partitionsDropped);
            return new Result(rule.name(), cutoff, total, partitionsDropped, partitionProblem);
        } catch (RuntimeException e) {
            log.error("Retention {} failed after removing {} rows; the next run continues", rule.name(), total, e);
            return new Result(rule.name(), cutoff, total, partitionsDropped, e.toString());
        } finally {
            if (total > 0) {
                Counter.builder("bank.retention.deleted").description("Rows deleted by the daily clean-up")
                    .tag("rule", rule.name()).register(meters).increment(total);
            }
            if (partitionsDropped > 0) {
                Counter.builder("bank.retention.partitions.dropped").description("Whole partitions dropped by the daily clean-up")
                    .tag("rule", rule.name()).register(meters).increment(partitionsDropped);
            }
        }
    }
}
