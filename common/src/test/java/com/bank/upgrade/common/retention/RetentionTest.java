package com.bank.upgrade.common.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.bank.upgrade.common.autoconfigure.RetentionAutoConfiguration;
import com.bank.upgrade.test.NoOpTransactionManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** The daily clean-up with the database mocked: batches, cutoffs, settings, failures, metrics and wiring. */
class RetentionTest {

    static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final NoOpTransactionManager tx = new NoOpTransactionManager();
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    /** A rule that "deletes" the given counts, one per call, and remembers the cutoffs and limits it was asked for. */
    static final class Scripted implements RetentionRule.Purge {
        final Deque<Integer> counts;
        final List<Instant> cutoffs = new ArrayList<>();
        final List<Integer> limits = new ArrayList<>();

        Scripted(Integer... counts) {
            this.counts = new ArrayDeque<>(List.of(counts));
        }

        @Override
        public int deleteBatch(Instant cutoff, int limit) {
            cutoffs.add(cutoff);
            limits.add(limit);
            return counts.isEmpty() ? 0 : counts.poll();
        }
    }

    private RetentionRunner runner(List<RetentionRule> rules, int batchSize, Map<String, Duration> keep) {
        return new RetentionRunner(rules, new RetentionProperties(true, "0 30 5 * * *", batchSize, keep), tx, clock, meters);
    }

    @Test
    void deletesInBatchesEachInItsOwnTransactionUntilAShortOne() {
        var purge = new Scripted(100, 100, 37);

        var results = runner(List.of(new RetentionRule("letters", Duration.ofDays(400), purge)), 100, Map.of()).runAll();

        assertThat(results).singleElement().satisfies(r -> {
            assertThat(r.deleted()).isEqualTo(237);
            assertThat(r.error()).isNull();
        });
        assertThat(purge.limits).containsExactly(100, 100, 100);
        assertThat(tx.commits).as("one transaction per batch").hasValue(3);
        assertThat(meters.get("bank.retention.deleted").tag("rule", "letters").counter().count()).isEqualTo(237);
    }

    @Test
    void theCutoffIsNowMinusHowLongTheRuleKeepsRowsAndASettingOverridesTheDefault() {
        var staging = new Scripted();
        var events = new Scripted();

        runner(List.of(new RetentionRule("staging", Duration.ofDays(30), staging),
                new RetentionRule("processed-events", Duration.ofDays(30), events)),
            1000, Map.of("processed-events", Duration.ofDays(14))).runAll();

        assertThat(staging.cutoffs).containsExactly(NOW.minus(Duration.ofDays(30)));
        assertThat(events.cutoffs).containsExactly(NOW.minus(Duration.ofDays(14)));
    }

    @Test
    void aRuleThatFailsIsReportedAndTheOthersStillRun() {
        RetentionRule.Purge broken = (cutoff, limit) -> {
            throw new IllegalStateException("ORA-00054: resource busy");
        };
        var fine = new Scripted(5);

        var results = runner(List.of(new RetentionRule("broken", Duration.ofDays(1), broken),
            new RetentionRule("fine", Duration.ofDays(1), fine)), 10, Map.of()).runAll();

        assertThat(results.get(0).error()).contains("resource busy");
        assertThat(results.get(1).deleted()).isEqualTo(5);
        assertThat(tx.rollbacks).hasValue(1);
    }

    @Test
    void nothingOldMeansOneQuickLookAndNoMetric() {
        var purge = new Scripted(0);

        runner(List.of(new RetentionRule("letters", Duration.ofDays(400), purge)), 100, Map.of()).runAll();

        assertThat(purge.limits).hasSize(1);
        assertThat(meters.find("bank.retention.deleted").counter()).isNull();
    }

    @Test
    void timestampRulesGetTheCutoffAsAUtcTimestampAndDateRulesAsADateInTheBanksZone() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Instant cutoff = Instant.parse("2025-10-01T20:00:00Z");   // already 2 October in India

        RetentionRule.olderThanTimestamp("t", Duration.ofDays(1), jdbc, "DELETE T").purge().deleteBatch(cutoff, 50);
        RetentionRule.olderThanDate("d", Duration.ofDays(1), jdbc, "DELETE D", ZoneId.of("Asia/Kolkata")).purge().deleteBatch(cutoff, 50);

        verify(jdbc).update("DELETE T", OffsetDateTime.parse("2025-10-01T20:00:00Z"), 50);
        verify(jdbc).update("DELETE D", LocalDate.of(2025, 10, 2), 50);
    }

    // --- wiring ---------------------------------------------------------------------------------------------

    // Every service gets bank.time-zone from bank-defaults.yml; a bare test context needs it set
    final ApplicationContextRunner context = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RetentionAutoConfiguration.class))
        .withPropertyValues("bank.time-zone=Asia/Kolkata")
        .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
        .withBean(PlatformTransactionManager.class, NoOpTransactionManager::new);

    @Test
    void everyServiceWithADatabaseGetsTheCleanUpAndConsumersAlsoTheProcessedEventRule() {
        context.run(ctx -> {
            assertThat(ctx).hasSingleBean(RetentionRunner.class);
            assertThat(ctx).doesNotHaveBean(RetentionRule.class);
        });
        context.withPropertyValues("bank.processed-events.enabled=true").run(ctx ->
            assertThat(ctx.getBean(RetentionRule.class).name()).isEqualTo("processed-events"));
    }

    @Test
    void itCanBeSwitchedOffAndIsAbsentWithoutADatabase() {
        context.withPropertyValues("bank.retention.enabled=false").run(ctx -> assertThat(ctx).doesNotHaveBean(RetentionRunner.class));
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(RetentionAutoConfiguration.class))
            .run(ctx -> assertThat(ctx).doesNotHaveBean(RetentionRunner.class));
    }

    @Test
    void theProcessedEventRuleDeletesByAgeInBatches() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RetentionAutoConfiguration.class))
            .withBean(JdbcTemplate.class, () -> jdbc)
            .withBean(PlatformTransactionManager.class, NoOpTransactionManager::new)
            .withPropertyValues("bank.processed-events.enabled=true", "bank.time-zone=Asia/Kolkata")
            .run(ctx -> ctx.getBean(RetentionRule.class).purge().deleteBatch(NOW, 10));
        verify(jdbc).update(contains("FROM PROCESSED_EVENT WHERE PROCESSED_AT < ?"), any(), eq(10));
    }
}
