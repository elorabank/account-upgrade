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
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * The 03:00 run, as one reactive pipeline:
 * <pre>
 *   claim PENDING requests, a batch at a time  (SKIP LOCKED, marked IN_PROGRESS for this run)
 *     -> call the core system, up to {@code concurrency} accounts at once  (non-blocking, no thread waits)
 *     -> record the outcomes, up to 100 per transaction, each with its outbox event
 * </pre>
 * A new batch is only claimed when the calls need more work, and recording runs one transaction after another, so
 * the database sees a steady, bounded load however many calls are in flight.
 * <p>
 * The core call is made outside any database transaction, with the idempotency key {@code <account>:<businessDate>}.
 * If the service dies before an outcome is recorded, the request is still IN_PROGRESS; the next run puts it back to
 * PENDING and repeats the call, which the core system ignores because the key is the same.
 * <p>
 * A temporary core-system problem is retried a few times at once; if it persists the request stays PENDING for the
 * next run and counts one attempt, and after {@code maxAttempts} runs it becomes FAILED. If
 * {@code abortAfterConsecutiveFailures} accounts in a row cannot be reached, the run stops starting new calls (calls
 * already in flight finish), so an outage does not use up every account's attempts.
 */
public class UpgradeRunner {

    private static final Logger log = LoggerFactory.getLogger(UpgradeRunner.class);
    private static final int RECORD_BATCH = 100;

    public record Summary(String runId, int upgraded, int failed, int deferred, int errors, boolean aborted) {}

    /** What a claimed request needs for its core call, read while claiming so the calls need no database reads. */
    record Claimed(long id, String accountNo, LocalDate businessDate, String currentType, String targetType, String correlationId) {}

    private enum Kind { UPGRADED, REFUSED, UNAVAILABLE, ERROR, SKIPPED }

    private record Outcome(Claimed request, Kind kind, String reason) {}

    private final UpgradeRequestRepository requests;
    private final AccountUpgradeGateway gateway;
    private final OutboxService outbox;
    private final UpgradeProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public UpgradeRunner(UpgradeRequestRepository requests, AccountUpgradeGateway gateway, OutboxService outbox,
            UpgradeProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.requests = requests;
        this.gateway = gateway;
        this.outbox = outbox;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Blocks until the run is over. Called from the scheduler or "run now" thread, never an event-loop thread. */
    public Summary runOnce() {
        String runId = UUID.randomUUID().toString();
        try (var scope = CorrelationId.open(runId)) {
            int reclaimed = tx.execute(s -> requests.reclaimInProgress());
            if (reclaimed > 0) {
                log.warn("{} request(s) were left in progress by an earlier run and are queued again", reclaimed);
            }
            var stop = new AtomicBoolean();
            var unreachableInARow = new AtomicInteger();
            var counts = new Counts();

            Mono.fromCallable(() -> claim(runId)).subscribeOn(Schedulers.boundedElastic())
                .repeat()
                .takeWhile(batch -> !batch.isEmpty() && !stop.get())
                .concatMapIterable(batch -> batch, 1)   // claim the next batch only when this one is used up
                .flatMap(request -> call(request, stop), properties.concurrency())
                .doOnNext(outcome -> watchForOutage(outcome, unreachableInARow, stop))
                .bufferTimeout(RECORD_BATCH, Duration.ofMillis(200), true)   // waits for the recorder, never overflows
                .concatMap(outcomes -> Mono.fromRunnable(() -> record(outcomes, counts)).subscribeOn(Schedulers.boundedElastic()))
                .blockLast();

            if (stop.get()) {
                // Claimed but never called: back to PENDING for the next run
                tx.executeWithoutResult(s -> requests.releaseRun(runId));
            }
            var summary = new Summary(runId, counts.upgraded.get(), counts.failed.get(), counts.deferred.get(),
                counts.errors.get(), stop.get());
            log.info("Upgrade run finished: {}", summary);
            return summary;
        }
    }

    private List<Claimed> claim(String runId) {
        return tx.execute(s -> {
            var locked = requests.lockNextPending(runId, properties.batchSize());
            return locked.stream().map(r -> {
                r.setStatus(UpgradeRequest.IN_PROGRESS);
                r.setRunId(runId);
                return new Claimed(r.getId(), r.getAccountNo(), r.getBusinessDate(), r.getCurrentType(), r.getTargetType(),
                    r.getCorrelationId() != null ? r.getCorrelationId() : runId);
            }).toList();
        });
    }

    /** One account's core call with its quick retries. Never fails: every problem becomes an outcome. */
    private Mono<Outcome> call(Claimed r, AtomicBoolean stop) {
        if (stop.get()) {
            return Mono.just(new Outcome(r, Kind.SKIPPED, null));
        }
        var command = new UpgradeCommand(r.accountNo() + ":" + r.businessDate(), r.accountNo(), r.currentType(), r.targetType());
        return Mono.defer(() -> gateway.upgrade(command))
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
        var toSave = outcomes.stream().filter(o -> o.kind() != Kind.SKIPPED && o.kind() != Kind.ERROR).toList();
        counts.errors.addAndGet((int) outcomes.stream().filter(o -> o.kind() == Kind.ERROR).count());
        if (toSave.isEmpty()) {
            return;
        }
        try {
            tx.executeWithoutResult(s -> {
                Map<Long, UpgradeRequest> byId = requests.findAllById(toSave.stream().map(o -> o.request().id()).toList())
                    .stream().collect(Collectors.toMap(UpgradeRequest::getId, Function.identity()));
                for (Outcome o : toSave) {
                    try (var scope = CorrelationId.open(o.request().correlationId())) {
                        apply(byId.get(o.request().id()), o, counts);
                    }
                }
            });
        } catch (RuntimeException e) {
            // The core calls may have succeeded; these requests stay IN_PROGRESS and are repeated next run
            log.error("{} outcome(s) could not be recorded, they are retried in the next run", toSave.size(), e);
            counts.errors.addAndGet(toSave.size());
        }
    }

    private void apply(UpgradeRequest r, Outcome o, Counts counts) {
        r.setAttemptCount(r.getAttemptCount() + 1);
        switch (o.kind()) {
            case UPGRADED -> {
                r.setStatus(UpgradeRequest.UPGRADED);
                r.setReason(null);
                outbox.publish(Topics.ACCOUNT_UPGRADED, r.getAccountNo(), "AccountUpgraded", r.getAccountNo(),
                    new AccountUpgraded(r.getId(), r.getAccountNo(), r.getCustomerId(), r.getCustomerName(), r.getEmail(),
                        r.getCurrentType(), r.getTargetType(), r.getBusinessDate(), clock.instant()),
                    null);
                counts.upgraded.incrementAndGet();
            }
            case REFUSED -> {
                fail(r, o.reason());
                counts.failed.incrementAndGet();
            }
            case UNAVAILABLE -> {
                if (r.getAttemptCount() >= properties.maxAttempts()) {
                    fail(r, "Gave up after " + r.getAttemptCount() + " runs. Last error: " + o.reason());
                    counts.failed.incrementAndGet();
                } else {
                    // Stays attached to this run's id so the run does not pick it up again
                    r.setStatus(UpgradeRequest.PENDING);
                    r.setReason(truncate("Core system unavailable: " + o.reason()));
                    counts.deferred.incrementAndGet();
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
