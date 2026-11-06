package com.bank.upgrade.upgrade.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bank.upgrade.common.correlation.CorrelationId;
import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.messaging.OutboxService;
import com.bank.upgrade.test.NoOpTransactionManager;
import com.bank.upgrade.upgrade.config.UpgradeProperties;
import com.bank.upgrade.upgrade.domain.UpgradeRequest;
import com.bank.upgrade.upgrade.domain.UpgradeRequestRepository;
import com.bank.upgrade.upgrade.gateway.AccountUpgradeGateway;
import com.bank.upgrade.upgrade.gateway.CoreCallLimiter;
import com.bank.upgrade.upgrade.gateway.GatewayRejectedException;
import com.bank.upgrade.upgrade.gateway.GatewayUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import reactor.core.publisher.Mono;

/**
 * The nightly run with the database, the core system and the outbox replaced by fakes. The fake table answers the
 * claim query the way the real one does (untried PENDING requests, and IN_PROGRESS ones whose lease ran out), so the
 * runner's own decisions are what is tested: calls, retries, outcomes, events, leases and metrics.
 */
class UpgradeRunnerTest {

    static final LocalDate DAY = LocalDate.of(2027, 1, 15);
    static final String COPY = "copy-A";

    /** The UPGRADE_REQUEST table in memory. */
    final Map<Long, UpgradeRequest> table = new ConcurrentHashMap<>();
    final AtomicLong ids = new AtomicLong();

    /** The core system: every call is recorded; scripted problems per account, otherwise success. */
    final List<String> calls = new CopyOnWriteArrayList<>();
    final Map<String, Deque<RuntimeException>> problems = new ConcurrentHashMap<>();
    final Map<String, RuntimeException> alwaysFails = new ConcurrentHashMap<>();
    volatile Duration latency = Duration.ZERO;
    volatile Consumer<String> duringCall = account -> {};
    final AccountUpgradeGateway core = command -> Mono.defer(() -> {
        calls.add(command.accountNo());
        duringCall.accept(command.accountNo());
        RuntimeException problem = alwaysFails.get(command.accountNo());
        Deque<RuntimeException> queue = problems.get(command.accountNo());
        if (problem == null && queue != null) {
            problem = queue.poll();
        }
        return problem != null ? Mono.<Void>error(problem) : Mono.delay(latency).then();
    });

    final UpgradeRequestRepository requests = mock(UpgradeRequestRepository.class);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final OutboxService outbox = mock(OutboxService.class);
    /** Events written to the outbox: topic, account, and the correlation id that was current. */
    final List<String> events = new CopyOnWriteArrayList<>();
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    int concurrency = 10;
    int abortAfter = 5;
    Duration lease = Duration.ofMinutes(2);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void fakes() throws Exception {
        when(jdbc.query(any(PreparedStatementCreator.class), any(RowMapper.class))).thenAnswer(inv -> {
            // Run the runner's statement against a fake connection to read its parameters, then answer like Oracle
            PreparedStatementCreator creator = inv.getArgument(0);
            Connection con = mock(Connection.class);
            PreparedStatement ps = mock(PreparedStatement.class);
            when(con.prepareStatement(anyString())).thenReturn(ps);
            creator.createPreparedStatement(con);
            ArgumentCaptor<String> runId = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Object> now = ArgumentCaptor.forClass(Object.class);
            ArgumentCaptor<Integer> max = ArgumentCaptor.forClass(Integer.class);
            verify(ps).setString(eq(1), runId.capture());
            verify(ps).setObject(eq(2), now.capture());
            verify(ps).setMaxRows(max.capture());
            return claimable(runId.getValue(), ((OffsetDateTime) now.getValue()).toInstant(), max.getValue());
        });
        when(requests.findAllById(anyIterable())).thenAnswer(inv -> {
            List<UpgradeRequest> found = new ArrayList<>();
            inv.<Iterable<Long>>getArgument(0).forEach(id -> {
                if (table.containsKey(id)) {
                    found.add(table.get(id));
                }
            });
            return found;
        });
        when(requests.renewClaims(anyString(), any())).thenAnswer(inv -> {
            int n = 0;
            for (var r : table.values()) {
                if (inv.getArgument(0).equals(r.getClaimedBy()) && UpgradeRequest.IN_PROGRESS.equals(r.getStatus())) {
                    r.setClaimedUntil(inv.getArgument(1));
                    n++;
                }
            }
            return n;
        });
        when(requests.releaseClaims(anyString())).thenAnswer(inv -> {
            int n = 0;
            for (var r : table.values()) {
                if (inv.getArgument(0).equals(r.getClaimedBy()) && UpgradeRequest.IN_PROGRESS.equals(r.getStatus())) {
                    r.setStatus(UpgradeRequest.PENDING);
                    r.setClaimedBy(null);
                    r.setClaimedUntil(null);
                    n++;
                }
            }
            return n;
        });
        when(requests.countByStatusAndClaimedByNot(anyString(), anyString())).thenAnswer(inv -> table.values().stream()
            .filter(r -> inv.getArgument(0).equals(r.getStatus()) && r.getClaimedBy() != null && !inv.getArgument(1).equals(r.getClaimedBy()))
            .count());
        when(outbox.publish(anyString(), anyString(), anyString(), anyString(), any(), any())).thenAnswer(inv -> {
            events.add(inv.getArgument(0) + " " + inv.getArgument(1) + " " + CorrelationId.current());
            return null;
        });
    }

    /** What the claim query returns: untried PENDING requests and IN_PROGRESS ones whose lease ran out, oldest first. */
    private synchronized List<Long> claimable(String runId, Instant now, int max) {
        return table.values().stream()
            .filter(r -> (UpgradeRequest.PENDING.equals(r.getStatus()) && !runId.equals(r.getRunId()))
                || (UpgradeRequest.IN_PROGRESS.equals(r.getStatus()) && (r.getClaimedUntil() == null || r.getClaimedUntil().isBefore(now))))
            .map(UpgradeRequest::getId)
            .sorted()
            .limit(max)
            .toList();
    }

    private UpgradeRequest queue(String account) {
        var r = new UpgradeRequest();
        r.setId(ids.incrementAndGet());
        r.setAccountNo(account);
        r.setCustomerId("C-" + account);
        r.setCustomerName("Customer " + account);
        r.setEmail(account + "@example.com");
        r.setCurrentType("SAVINGS");
        r.setTargetType("SAVINGS_PLUS");
        r.setBusinessDate(DAY);
        r.setCorrelationId("file-1");
        table.put(r.getId(), r);
        return r;
    }

    private UpgradeRequest row(String account) {
        return table.values().stream().filter(r -> r.getAccountNo().equals(account)).findFirst().orElseThrow();
    }

    private UpgradeRunner runner() {
        var properties = new UpgradeProperties("http://ingestion", 25, concurrency, 3, Duration.ZERO, 3, abortAfter,
            new UpgradeProperties.Rules(null, "VERIFIED", BigDecimal.ZERO), new UpgradeProperties.Simulated(Duration.ZERO, 0),
            COPY, lease, 0);
        return new UpgradeRunner(requests, core, CoreCallLimiter.none(), outbox, properties, new NoOpTransactionManager(),
            jdbc, Clock.systemUTC(), meters, COPY);
    }

    private double counted(String result) {
        var counter = meters.find("upgrade.requests").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void everyPendingAccountIsUpgradedOnceWithOneEventEach() {
        queue("A1");
        queue("A2");
        queue("A3");

        var summary = runner().runOnce();

        assertThat(summary.upgraded()).isEqualTo(3);
        assertThat(calls).containsExactlyInAnyOrder("A1", "A2", "A3");
        assertThat(table.values()).allSatisfy(r -> {
            assertThat(r.getStatus()).isEqualTo(UpgradeRequest.UPGRADED);
            assertThat(r.getAttemptCount()).isEqualTo(1);
            assertThat(r.getClaimedBy()).isNull();
            assertThat(r.getClaimedUntil()).isNull();
        });
        assertThat(events).hasSize(3).allMatch(e -> e.startsWith(Topics.ACCOUNT_UPGRADED));
        assertThat(counted("upgraded")).isEqualTo(3);
        assertThat(meters.get("upgrade.core.call").tag("outcome", "ok").timer().count()).isEqualTo(3);
        assertThat(meters.get("upgrade.requests.held").gauge().value()).isZero();
        assertThat(meters.get("upgrade.run.active").gauge().value()).isZero();
    }

    @Test
    void theCoreSystemGetsTheSameIdempotencyKeyOnEveryTry() {
        queue("K1");
        problems.put("K1", new ArrayDeque<>(List.of(new GatewayUnavailableException("timeout"))));
        var keys = new CopyOnWriteArrayList<String>();
        AccountUpgradeGateway recording = command -> {
            keys.add(command.idempotencyKey());
            return core.upgrade(command);
        };
        var properties = new UpgradeProperties("http://ingestion", 25, 10, 3, Duration.ZERO, 3, 5,
            new UpgradeProperties.Rules(null, "VERIFIED", BigDecimal.ZERO), new UpgradeProperties.Simulated(Duration.ZERO, 0),
            COPY, lease, 0);
        new UpgradeRunner(requests, recording, CoreCallLimiter.none(), outbox, properties, new NoOpTransactionManager(),
            jdbc, Clock.systemUTC(), meters, COPY).runOnce();

        assertThat(keys).containsExactly("K1:" + DAY, "K1:" + DAY);
    }

    @Test
    void eachEventCarriesTheCorrelationIdOfTheFileThatQueuedTheRequest() {
        queue("C1").setCorrelationId("file-77");

        runner().runOnce();

        assertThat(events).containsExactly(Topics.ACCOUNT_UPGRADED + " C1 file-77");
    }

    @Test
    void runningAgainDoesNothingBecauseNothingIsPending() {
        queue("A1");
        var runner = runner();
        runner.runOnce();

        var second = runner.runOnce();

        assertThat(second.upgraded()).isZero();
        assertThat(calls).containsExactly("A1");
    }

    @Test
    void aRefusalFromTheCoreSystemFailsOnlyThatAccount() {
        queue("OK1");
        queue("NO1");
        alwaysFails.put("NO1", new GatewayRejectedException("account is frozen"));

        var summary = runner().runOnce();

        assertThat(summary.upgraded()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(row("NO1").getStatus()).isEqualTo(UpgradeRequest.FAILED);
        assertThat(row("NO1").getReason()).contains("account is frozen");
        assertThat(calls).filteredOn("NO1"::equals).as("a refusal is not retried").hasSize(1);
        assertThat(events).contains(Topics.UPGRADE_FAILED + " NO1 file-1");
    }

    @Test
    void aShortOutageIsRiddenOutWithinTheRun() {
        queue("S1");
        problems.put("S1", new ArrayDeque<>(List.of(new GatewayUnavailableException("timeout"), new GatewayUnavailableException("timeout"))));

        var summary = runner().runOnce();

        assertThat(summary.upgraded()).isEqualTo(1);
        assertThat(calls).containsExactly("S1", "S1", "S1");
        assertThat(meters.get("upgrade.core.call").tag("outcome", "unavailable").timer().count()).isEqualTo(2);
    }

    @Test
    void aLongerOutageCountsOneAttemptPerRunAndFailsAfterTheLimit() {
        queue("L1");
        alwaysFails.put("L1", new GatewayUnavailableException("down"));

        var first = runner().runOnce();
        assertThat(first.deferred()).isEqualTo(1);
        assertThat(row("L1").getStatus()).isEqualTo(UpgradeRequest.PENDING);
        assertThat(row("L1").getAttemptCount()).isEqualTo(1);
        assertThat(row("L1").getReason()).contains("unavailable");

        runner().runOnce();
        var third = runner().runOnce();
        assertThat(third.failed()).isEqualTo(1);
        assertThat(row("L1").getStatus()).isEqualTo(UpgradeRequest.FAILED);
        assertThat(row("L1").getAttemptCount()).isEqualTo(3);
    }

    @Test
    void anOutageStopsTheRunInsteadOfUsingUpEveryAccountsAttempts() {
        concurrency = 1;
        for (int i = 1; i <= 7; i++) {
            queue("O" + i);
            alwaysFails.put("O" + i, new GatewayUnavailableException("down"));
        }

        var summary = runner().runOnce();

        assertThat(summary.aborted()).isTrue();
        assertThat(summary.deferred()).isEqualTo(5);
        assertThat(table.values()).allMatch(r -> UpgradeRequest.PENDING.equals(r.getStatus()) && r.getClaimedBy() == null);
        assertThat(table.values()).filteredOn(r -> r.getAttemptCount() == 0).as("never reached, ready for the next run").hasSize(2);
        verify(requests).releaseClaims(COPY);
    }

    @Test
    void aRequestLeftInProgressByADeadCopyIsTakenOverOnceItsLeaseRanOut() {
        var dead = queue("D1");
        dead.setStatus(UpgradeRequest.IN_PROGRESS);
        dead.setClaimedBy("dead-copy");
        dead.setClaimedUntil(Instant.now().minusSeconds(60));

        var summary = runner().runOnce();

        assertThat(summary.upgraded()).isEqualTo(1);
        assertThat(meters.get("upgrade.leases.taken.over").counter().count()).isEqualTo(1);
    }

    @Test
    void aCopyWithNothingLeftWaitsForADyingCopysLeaseAndThenTakesItsWorkOver() {
        lease = Duration.ofMillis(600);   // look again every 100 ms
        queue("FREE");
        var dying = queue("LATE");
        dying.setStatus(UpgradeRequest.IN_PROGRESS);
        dying.setClaimedBy("dying-copy");
        Instant leaseEnds = Instant.now().plusMillis(700);
        dying.setClaimedUntil(leaseEnds);

        var summary = runner().runOnce();

        assertThat(summary.upgraded()).isEqualTo(2);
        assertThat(Instant.now()).as("the run waited for the lease").isAfter(leaseEnds);
    }

    @Test
    void aLiveCopysRequestsAreLeftAloneAndTheRunEndsWhenThatCopyIsDone() throws Exception {
        lease = Duration.ofMillis(600);
        queue("FREE");
        var held = queue("HELD");
        held.setStatus(UpgradeRequest.IN_PROGRESS);
        held.setClaimedBy("live-copy");
        held.setClaimedUntil(Instant.now().plusSeconds(60));

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var run = threads.submit(runner()::runOnce);
            Thread.sleep(500);
            assertThat(run.isDone()).as("waits while another copy holds a request").isFalse();
            // The live copy finishes its request
            held.setStatus(UpgradeRequest.UPGRADED);
            held.setClaimedBy(null);
            held.setClaimedUntil(null);
            assertThat(run.get().upgraded()).isEqualTo(1);
        }
        assertThat(calls).containsExactly("FREE");
    }

    @Test
    void anOutcomeIsNotRecordedForARequestAnotherCopyTookOverMeanwhile() {
        lease = Duration.ofMillis(600);
        var r = queue("T1");
        duringCall = account -> {
            // While the call is under way this copy's lease runs out and another copy claims the request...
            r.setClaimedBy("other-copy");
            r.setClaimedUntil(Instant.now().plusSeconds(60));
            // ...and finishes it a moment later, so this copy's run can end
            Thread.ofVirtual().start(() -> {
                sleep(300);
                r.setStatus(UpgradeRequest.UPGRADED);
                r.setClaimedBy(null);
            });
        };

        runner().runOnce();

        assertThat(counted("lost")).isEqualTo(1);
        assertThat(counted("upgraded")).isZero();
        assertThat(events).as("the other copy publishes, not this one").isEmpty();
    }

    @Test
    void aWorkingCopyKeepsRenewingItsLeases() {
        lease = Duration.ofMillis(600);   // renewed every 200 ms
        latency = Duration.ofMillis(300);
        concurrency = 1;
        queue("R1");
        queue("R2");
        queue("R3");

        runner().runOnce();

        verify(requests, atLeastOnce()).renewClaims(eq(COPY), any());
        assertThat(calls).hasSize(3).doesNotHaveDuplicates();
    }

    @Test
    void aSecondTriggerWhileBusyIsIgnored() throws Exception {
        latency = Duration.ofMillis(300);
        queue("B1");
        var runner = runner();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = threads.submit(() -> runner.runIfIdle("run-1"));
            Thread.sleep(100);
            assertThat(runner.busy()).isTrue();
            assertThat(runner.runIfIdle("run-1")).isEmpty();
            assertThat(first.get()).isPresent();
        }
        assertThat(runner.busy()).isFalse();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
