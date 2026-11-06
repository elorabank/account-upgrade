package com.bank.upgrade.upgrade.run;

import com.bank.upgrade.common.correlation.CorrelationId;
import com.bank.upgrade.common.events.AccountUpgraded;
import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.events.UpgradeFailed;
import com.bank.upgrade.common.messaging.OutboxService;
import com.bank.upgrade.upgrade.config.UpgradeProperties;
import com.bank.upgrade.upgrade.domain.UpgradeRequest;
import com.bank.upgrade.upgrade.domain.UpgradeRequestRepository;
import com.bank.upgrade.upgrade.gateway.AccountUpgradeGateway;
import com.bank.upgrade.upgrade.gateway.GatewayRejectedException;
import com.bank.upgrade.upgrade.gateway.GatewayUnavailableException;
import com.bank.upgrade.upgrade.gateway.UpgradeCommand;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.bank.upgrade.upgrade.gateway.CoreCallLimiter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * One copy's share of an upgrade run, as one reactive pipeline:
 * <pre>
 *   claim PENDING requests, a batch at a time  (SKIP LOCKED, marked IN_PROGRESS with this copy's lease)
 *     -> call the core system, up to {@code concurrency} accounts at once  (non-blocking, no thread waits)
 *     -> record the outcomes, up to 100 per transaction, each with its outbox event
 * </pre>
 * A new batch is only claimed when the calls need more work, and recording runs one transaction after another, so
 * the database sees a steady, bounded load however many calls are in flight.
 * <p>
 * <b>Several copies.</b> Every copy of the service runs this at the same time with the same run id; they share the work
 * because each claim locks different rows. A claim is a lease ({@code CLAIMED_BY}, {@code CLAIMED_UNTIL}) that the copy
 * renews while it works. If a copy dies, its leases run out and another copy takes those requests over; a copy with
 * nothing left to claim waits while others still hold requests, so the run ends only when all are done. A copy only
 * records outcomes for requests it still holds, and if it stops early it hands back only its own claims. Core-system
 * calls of all copies together can be held to a rate through Redis ({@link CoreCallLimiter}).
 * <p>
 * The core call is made outside any database transaction, with the idempotency key {@code <account>:<businessDate>}.
 * If a copy dies before an outcome is recorded, the request stays IN_PROGRESS until its lease runs out; then any copy
 * claims it again and repeats the call, which the core system ignores because the key is the same.
 * <p>
 * A temporary core-system problem is retried a few times at once; if it persists the request stays PENDING for the
 * next run and counts one attempt, and after {@code maxAttempts} runs it becomes FAILED. If
 * {@code abortAfterConsecutiveFailures} accounts in a row cannot be reached, the run stops starting new calls (calls
 * already in flight finish), so an outage does not use up every account's attempts.
 * <p>
 * Metrics (each copy reports its own; Prometheus tells them apart by instance): {@code upgrade.core.call} per try, by
 * outcome; {@code upgrade.core.limit.wait}, time spent waiting for the shared limit; {@code upgrade.requests}, finished
 * requests by result; {@code upgrade.leases.taken.over}; and the gauges {@code upgrade.requests.held} and
 * {@code upgrade.run.active}.
 */
public class UpgradeRunner {

    private static final Logger log = LoggerFactory.getLogger(UpgradeRunner.class);
    private static final int RECORD_BATCH = 100;

    public record Summary(String runId, String copy, int upgraded, int failed, int deferred, int errors, boolean aborted) {}

    private static final String CLAIM_SQL = """
        SELECT ID FROM UPGRADE_REQUEST
        WHERE (STATUS = 'PENDING' AND (RUN_ID IS NULL OR RUN_ID <> ?))
           OR (STATUS = 'IN_PROGRESS' AND (CLAIMED_UNTIL IS NULL OR CLAIMED_UNTIL < ?))
        FOR UPDATE SKIP LOCKED
        """;

    /** What a claimed request needs for its core call, read while claiming so the calls need no database reads. */
    record Claimed(long id, String accountNo, LocalDate businessDate, String currentType, String targetType, String correlationId) {}

    private enum Kind { UPGRADED, REFUSED, UNAVAILABLE, ERROR, SKIPPED }

    private record Outcome(Claimed request, Kind kind, String reason) {}

    private final UpgradeRequestRepository requests;
    private final AccountUpgradeGateway gateway;
    private final CoreCallLimiter limiter;
    private final OutboxService outbox;
    private final UpgradeProperties properties;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String copy;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final MeterRegistry meters;
    private final AtomicInteger held = new AtomicInteger();
    private final Timer limitWait;
    private final Counter takenOver;

    public UpgradeRunner(UpgradeRequestRepository requests, AccountUpgradeGateway gateway, CoreCallLimiter limiter,
            OutboxService outbox, UpgradeProperties properties, PlatformTransactionManager transactionManager,
            JdbcTemplate jdbc, Clock clock, MeterRegistry meters) {
        this(requests, gateway, limiter, outbox, properties, transactionManager, jdbc, clock, meters, properties.instanceId());
    }

    /** With an explicit copy name: tests use this to play several copies in one JVM. */
    public UpgradeRunner(UpgradeRequestRepository requests, AccountUpgradeGateway gateway, CoreCallLimiter limiter,
            OutboxService outbox, UpgradeProperties properties, PlatformTransactionManager transactionManager,
            JdbcTemplate jdbc, Clock clock, MeterRegistry meters, String copy) {
        this.requests = requests;
        this.gateway = gateway;
        this.limiter = limiter;
        this.outbox = outbox;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
        this.clock = clock;
        this.copy = copy;
        this.meters = meters;
        Gauge.builder("upgrade.run.active", busy, b -> b.get() ? 1 : 0)
            .description("1 while this copy works on a run").register(meters);
        Gauge.builder("upgrade.requests.held", held, AtomicInteger::get)
            .description("Requests this copy has claimed and not yet finished").register(meters);
        Gauge.builder("upgrade.core.limit.per.second", properties, UpgradeProperties::coreCallsPerSecond)
            .description("Core calls per second allowed for all copies together (0 = no shared limit)").register(meters);
        this.limitWait = Timer.builder("upgrade.core.limit.wait")
            .description("Time a core call waited for the shared per-second limit")
            .publishPercentileHistogram().minimumExpectedValue(Duration.ofMillis(1)).maximumExpectedValue(Duration.ofSeconds(5))
            .register(meters);
        this.takenOver = Counter.builder("upgrade.leases.taken.over")
            .description("Requests taken over from a copy whose lease ran out").register(meters);
    }

    public String copy() {
        return copy;
    }

    /** True while this copy is working on a run. */
    public boolean busy() {
        return busy.get();
    }

    /** Joins the run unless this copy is already busy with one (a second trigger for the same run is then ignored). */
    public java.util.Optional<Summary> runIfIdle(String runId) {
        if (!busy.compareAndSet(false, true)) {
            log.info("Already working on a run, not joining {}", runId);
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(run(runId));
        } finally {
            busy.set(false);
        }
    }

    /** A run of its own, for tests and single-copy use. */
    public Summary runOnce() {
        return runIfIdle(UUID.randomUUID().toString()).orElseThrow(() -> new IllegalStateException("This copy is busy"));
    }

    /** Blocks until this copy's share is done. Called from the scheduler or "run now" thread, never an event-loop thread. */
    private Summary run(String runId) {
        try (var scope = CorrelationId.open(runId)) {
            log.info("Copy {} joins upgrade run {}", copy, runId);
            var stop = new AtomicBoolean();
            var unreachableInARow = new AtomicInteger();
            var counts = new Counts();
            // Keep this copy's leases alive while it works; a copy that dies stops renewing and its leases run out
            Disposable heartbeat = Flux.interval(properties.lease().dividedBy(3))
                .onBackpressureDrop()
                .concatMap(tick -> Mono.fromRunnable(this::renewLeases).subscribeOn(Schedulers.boundedElastic()), 1)
                .subscribe();

            try {
            nextBatch(runId, stop)
                .repeat()
                .takeWhile(batch -> !batch.isEmpty() && !stop.get())
                .concatMapIterable(batch -> batch, 1)   // claim the next batch only when this one is used up
                .flatMap(request -> call(request, stop), properties.concurrency())
                .doOnNext(outcome -> watchForOutage(outcome, unreachableInARow, stop))
                .bufferTimeout(RECORD_BATCH, Duration.ofMillis(200), true)   // waits for the recorder, never overflows
                .concatMap(outcomes -> Mono.fromRunnable(() -> record(outcomes, counts)).subscribeOn(Schedulers.boundedElastic()))
                .blockLast();
            } finally {
                heartbeat.dispose();
            }

            if (stop.get()) {
                // Claimed but never called: back to PENDING for the next run (only this copy's claims)
                tx.executeWithoutResult(s -> requests.releaseClaims(copy));
            }
            held.set(0);
            var summary = new Summary(runId, copy, counts.upgraded.get(), counts.failed.get(), counts.deferred.get(),
                counts.errors.get(), stop.get());
            log.info("Upgrade run finished: {}", summary);
            return summary;
        }
    }

    /**
     * The next batch to work on. When nothing is free but other copies still hold requests, waits and looks again: a
     * live copy finishes its requests, and a dead copy's become free once its leases run out. So the run only ends
     * when nothing is in progress anywhere, and a copy that dies near the end does not leave its share for tomorrow.
     * Empty when the run is over, or when this copy is stopping.
     */
    private Mono<List<Claimed>> nextBatch(String runId, AtomicBoolean stop) {
        return Mono.fromCallable(() -> claim(runId)).subscribeOn(Schedulers.boundedElastic())
            .flatMap(batch -> {
                if (!batch.isEmpty() || stop.get()) {
                    return Mono.just(batch);
                }
                long heldByOthers = requests.countByStatusAndClaimedByNot(UpgradeRequest.IN_PROGRESS, copy);
                if (heldByOthers == 0) {
                    return Mono.just(batch);
                }
                log.info("Nothing left to claim; {} requests are still held by other copies, looking again in {}",
                    heldByOthers, lookAgainAfter());
                return Mono.delay(lookAgainAfter()).then(Mono.defer(() -> nextBatch(runId, stop)));
            });
    }

    /** A sixth of the lease, between 100 ms and 10 s: soon enough after another copy finishes or its lease runs out. */
    private Duration lookAgainAfter() {
        Duration sixth = properties.lease().dividedBy(6);
        Duration min = Duration.ofMillis(100);
        Duration max = Duration.ofSeconds(10);
        return sixth.compareTo(min) < 0 ? min : sixth.compareTo(max) > 0 ? max : sixth;
    }

    /**
     * Locks the next batch for this copy: PENDING requests not yet tried in this run, and IN_PROGRESS ones whose lease
     * ran out (their copy died). Oracle locks rows with SKIP LOCKED as they are fetched, and the fetch stops after
     * batchSize rows, so concurrent copies each get the next free rows instead of tripping over each other's.
     */
    private List<Claimed> claim(String runId) {
        return tx.execute(s -> {
            Instant now = clock.instant();
            List<Long> ids = jdbc.query(con -> {
                PreparedStatement ps = con.prepareStatement(CLAIM_SQL);
                ps.setString(1, runId);
                ps.setObject(2, now.atOffset(ZoneOffset.UTC));
                ps.setMaxRows(properties.batchSize());
                ps.setFetchSize(properties.batchSize());
                return ps;
            }, (rs, n) -> rs.getLong(1));
            if (ids.isEmpty()) {
                return List.<Claimed>of();
            }
            Instant until = now.plus(properties.lease());
            held.addAndGet(ids.size());
            return requests.findAllById(ids).stream().map(r -> {
                if (UpgradeRequest.IN_PROGRESS.equals(r.getStatus())) {
                    log.warn("Taking over request {} from copy {} whose lease ran out", r.getId(), r.getClaimedBy());
                    takenOver.increment();
                }
                r.setStatus(UpgradeRequest.IN_PROGRESS);
                r.setRunId(runId);
                r.setClaimedBy(copy);
                r.setClaimedUntil(until);
                return new Claimed(r.getId(), r.getAccountNo(), r.getBusinessDate(), r.getCurrentType(), r.getTargetType(),
                    r.getCorrelationId() != null ? r.getCorrelationId() : runId);
            }).toList();
        });
    }

    private void renewLeases() {
        try {
            tx.executeWithoutResult(s -> requests.renewClaims(copy, clock.instant().plus(properties.lease())));
        } catch (RuntimeException e) {
            log.warn("Could not renew leases of copy {}: {}", copy, e.toString());
        }
    }

    /** One account's core call with its quick retries. Never fails: every problem becomes an outcome. */
    private Mono<Outcome> call(Claimed r, AtomicBoolean stop) {
        if (stop.get()) {
            return Mono.just(new Outcome(r, Kind.SKIPPED, null));
        }
        var command = new UpgradeCommand(r.accountNo() + ":" + r.businessDate(), r.accountNo(), r.currentType(), r.targetType());
        // Each try first gets permission from the shared limit, then calls the core system
        return Mono.defer(() -> {
                long waitStart = System.nanoTime();
                return limiter.acquire()
                    .doOnSuccess(v -> limitWait.record(System.nanoTime() - waitStart, TimeUnit.NANOSECONDS))
                    .then(Mono.defer(() -> {
                        long callStart = System.nanoTime();
                        return gateway.upgrade(command)
                            .doOnSuccess(v -> timeCall("ok", callStart))
                            .doOnError(e -> timeCall(outcomeOf(e), callStart));
                    }));
            })
            .retryWhen(quickRetries())
            .thenReturn(new Outcome(r, Kind.UPGRADED, null))
            .onErrorResume(GatewayRejectedException.class, e -> Mono.just(new Outcome(r, Kind.REFUSED, e.getMessage())))
            .onErrorResume(GatewayUnavailableException.class, e -> Mono.just(new Outcome(r, Kind.UNAVAILABLE, e.getMessage())))
            .onErrorResume(e -> {
                // Unexpected: leave it IN_PROGRESS, the next run repeats it
                log.error("Upgrade call for request {} failed unexpectedly", r.id(), e);
                return Mono.just(new Outcome(r, Kind.ERROR, e.toString()));
            });
    }

    private void timeCall(String outcome, long started) {
        Timer.builder("upgrade.core.call").description("Calls to the core system, one per try, by outcome")
            .tag("outcome", outcome)
            .publishPercentileHistogram().minimumExpectedValue(Duration.ofMillis(1)).maximumExpectedValue(Duration.ofSeconds(30))
            .register(meters).record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }

    private static String outcomeOf(Throwable e) {
        return e instanceof GatewayRejectedException ? "refused" : e instanceof GatewayUnavailableException ? "unavailable" : "error";
    }

    private void finished(String result, int n) {
        if (n > 0) {
            Counter.builder("upgrade.requests").description("Requests this copy finished, by result")
                .tag("result", result).register(meters).increment(n);
        }
    }

    /** Up to inlineAttempts tries in all, waiting retryBackoff, then twice that, ... Only for "unavailable". */
    private Retry quickRetries() {
        int retries = Math.max(properties.inlineAttempts() - 1, 0);
        Duration wait = properties.retryBackoff();
        if (wait.isZero()) {
            return Retry.max(retries).filter(GatewayUnavailableException.class::isInstance)
                .onRetryExhaustedThrow((s, signal) -> signal.failure());
        }
        return Retry.backoff(retries, wait).jitter(0).filter(GatewayUnavailableException.class::isInstance)
            .onRetryExhaustedThrow((s, signal) -> signal.failure());
    }

    private void watchForOutage(Outcome outcome, AtomicInteger unreachableInARow, AtomicBoolean stop) {
        if (outcome.kind() == Kind.UPGRADED) {
            unreachableInARow.set(0);
        } else if (outcome.kind() == Kind.UNAVAILABLE
                && unreachableInARow.incrementAndGet() >= properties.abortAfterConsecutiveFailures()
                && stop.compareAndSet(false, true)) {
            log.error("{} accounts in a row could not be reached, stopping the run", unreachableInARow.get());
        }
    }

    /** Saves a batch of outcomes, and their events, in one transaction. */
    private void record(List<Outcome> outcomes, Counts counts) {
        held.addAndGet(-outcomes.size());
        var toSave = outcomes.stream().filter(o -> o.kind() != Kind.SKIPPED && o.kind() != Kind.ERROR).toList();
        int errors = (int) outcomes.stream().filter(o -> o.kind() == Kind.ERROR).count();
        counts.errors.addAndGet(errors);
        finished("error", errors);
        finished("skipped", (int) outcomes.stream().filter(o -> o.kind() == Kind.SKIPPED).count());
        if (toSave.isEmpty()) {
            return;
        }
        // Counted once the transaction has committed, so a rollback is not counted twice
        Map<String, Integer> results = new java.util.HashMap<>();
        try {
            tx.executeWithoutResult(s -> {
                results.clear();
                Map<Long, UpgradeRequest> byId = requests.findAllById(toSave.stream().map(o -> o.request().id()).toList())
                    .stream().collect(Collectors.toMap(UpgradeRequest::getId, Function.identity()));
                for (Outcome o : toSave) {
                    UpgradeRequest r = byId.get(o.request().id());
                    if (r == null || !copy.equals(r.getClaimedBy()) || !UpgradeRequest.IN_PROGRESS.equals(r.getStatus())) {
                        // This copy's lease ran out and another copy took the request over; its outcome counts
                        log.warn("Request {} is no longer held by copy {}, not recording", o.request().id(), copy);
                        counts.errors.incrementAndGet();
                        results.merge("lost", 1, Integer::sum);
                        continue;
                    }
                    try (var scope = CorrelationId.open(o.request().correlationId())) {
                        results.merge(apply(r, o, counts), 1, Integer::sum);
                    }
                }
            });
            results.forEach(this::finished);
        } catch (RuntimeException e) {
            // The core calls may have succeeded; these requests stay IN_PROGRESS and are repeated next run
            log.error("{} outcome(s) could not be recorded, they are retried in the next run", toSave.size(), e);
            counts.errors.addAndGet(toSave.size());
            finished("error", toSave.size());
        }
    }

    /** Saves one outcome; returns the result for the metrics. */
    private String apply(UpgradeRequest r, Outcome o, Counts counts) {
        r.setAttemptCount(r.getAttemptCount() + 1);
        r.setClaimedBy(null);
        r.setClaimedUntil(null);
        switch (o.kind()) {
            case UPGRADED -> {
                r.setStatus(UpgradeRequest.UPGRADED);
                r.setReason(null);
                outbox.publish(Topics.ACCOUNT_UPGRADED, r.getAccountNo(), "AccountUpgraded", r.getAccountNo(),
                    new AccountUpgraded(r.getId(), r.getAccountNo(), r.getCustomerId(), r.getCustomerName(), r.getEmail(),
                        r.getCurrentType(), r.getTargetType(), r.getBusinessDate(), clock.instant()),
                    null);
                counts.upgraded.incrementAndGet();
                return "upgraded";
            }
            case REFUSED -> {
                fail(r, o.reason());
                counts.failed.incrementAndGet();
                return "failed";
            }
            case UNAVAILABLE -> {
                if (r.getAttemptCount() >= properties.maxAttempts()) {
                    fail(r, "Gave up after " + r.getAttemptCount() + " runs. Last error: " + o.reason());
                    counts.failed.incrementAndGet();
                    return "failed";
                } else {
                    // Stays attached to this run's id so the run does not pick it up again
                    r.setStatus(UpgradeRequest.PENDING);
                    r.setReason(truncate("Core system unavailable: " + o.reason()));
                    counts.deferred.incrementAndGet();
                    return "deferred";
                }
            }
            default -> throw new IllegalStateException("not recorded: " + o.kind());
        }
    }

    private void fail(UpgradeRequest r, String reason) {
        r.setStatus(UpgradeRequest.FAILED);
        r.setReason(truncate(reason));
        outbox.publish(Topics.UPGRADE_FAILED, r.getAccountNo(), "UpgradeFailed", r.getAccountNo(),
            new UpgradeFailed(r.getId(), r.getAccountNo(), r.getCustomerId(), r.getCurrentType(), r.getTargetType(),
                r.getBusinessDate(), r.getReason(), r.getAttemptCount()),
            null);
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() <= 1000 ? s : s.substring(0, 1000);
    }

    private static final class Counts {
        final AtomicInteger upgraded = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger deferred = new AtomicInteger();
        final AtomicInteger errors = new AtomicInteger();
    }
}
