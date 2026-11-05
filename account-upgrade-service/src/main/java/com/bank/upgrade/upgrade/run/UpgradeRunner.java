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
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The 03:00 run. Claims PENDING requests in batches, asks the core system to upgrade each account, and records the
 * result together with its outbox event in one transaction. The core call is made outside any database transaction.
 * <p>
 * If the service dies between the core call and the database update, the request is still IN_PROGRESS; the next run
 * puts it back to PENDING and repeats the call, which the core system ignores because the idempotency key is the same.
 * <p>
 * A temporary core-system problem is retried a few times at once; if it persists the request stays PENDING for the
 * next run and counts one attempt, and after {@code maxAttempts} runs it becomes FAILED. If several accounts in a row
 * cannot be reached the run stops, so an outage does not use up every account's attempts.
 */
public class UpgradeRunner {

    private static final Logger log = LoggerFactory.getLogger(UpgradeRunner.class);

    public record Summary(String runId, int upgraded, int failed, int deferred, int errors, boolean aborted) {}

    private enum Result { UPGRADED, FAILED, DEFERRED }

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

    public Summary runOnce() {
        String runId = UUID.randomUUID().toString();
        try (var scope = CorrelationId.open(runId)) {
            int reclaimed = tx.execute(s -> requests.reclaimInProgress());
            if (reclaimed > 0) {
                log.warn("{} request(s) were left in progress by an earlier run and are queued again", reclaimed);
            }
            int upgraded = 0, failed = 0, deferred = 0, errors = 0, consecutiveDeferred = 0;
            boolean aborted = false;
            batches:
            while (true) {
                List<Long> batch = claim(runId);
                if (batch.isEmpty()) {
                    break;
                }
                for (Long id : batch) {
                    Result result;
                    try {
                        result = process(id);
                    } catch (RuntimeException e) {
                        // The core call may have succeeded; the request stays IN_PROGRESS and is repeated next run
                        log.error("Request {} could not be recorded, it is retried in the next run", id, e);
                        errors++;
                        continue;
                    }
                    switch (result) {
                        case UPGRADED -> { upgraded++; consecutiveDeferred = 0; }
                        case FAILED -> failed++;
                        case DEFERRED -> {
                            deferred++;
                            if (++consecutiveDeferred >= properties.abortAfterConsecutiveFailures()) {
                                log.error("{} accounts in a row could not be reached, stopping the run", consecutiveDeferred);
                                aborted = true;
                                tx.executeWithoutResult(s -> requests.releaseRun(runId));
                                break batches;
                            }
                        }
                    }
                }
            }
            var summary = new Summary(runId, upgraded, failed, deferred, errors, aborted);
            log.info("Upgrade run finished: {}", summary);
            return summary;
        }
    }

    private List<Long> claim(String runId) {
        return tx.execute(s -> {
            var locked = requests.lockNextPending(runId, properties.batchSize());
            locked.forEach(r -> {
                r.setStatus(UpgradeRequest.IN_PROGRESS);
                r.setRunId(runId);
            });
            return locked.stream().map(UpgradeRequest::getId).toList();
        });
    }

    private Result process(Long id) {
        UpgradeRequest request = requests.findById(id).orElseThrow();
        // Events and log lines for this account carry the id of the file load that queued it
        String correlationId = request.getCorrelationId() != null ? request.getCorrelationId() : CorrelationId.current();
        try (var scope = CorrelationId.open(correlationId)) {
            return upgrade(id, request);
        }
    }

    private Result upgrade(Long id, UpgradeRequest request) {
        var command = new UpgradeCommand(
            request.getAccountNo() + ":" + request.getBusinessDate(),
            request.getAccountNo(), request.getCurrentType(), request.getTargetType());

        RuntimeException unavailable = null;
        for (int attempt = 1; attempt <= properties.inlineAttempts(); attempt++) {
            try {
                gateway.upgrade(command);
                markUpgraded(id);
                return Result.UPGRADED;
            } catch (GatewayRejectedException e) {
                markFailed(id, e.getMessage(), false);
                return Result.FAILED;
            } catch (GatewayUnavailableException e) {
                unavailable = e;
                pause(attempt);
            }
        }
        return defer(id, unavailable.getMessage());
    }

    private void pause(int attempt) {
        long millis = properties.retryBackoff().toMillis() * (1L << (attempt - 1));
        if (millis > 0) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting to retry", e);
            }
        }
    }

    private void markUpgraded(Long id) {
        tx.executeWithoutResult(s -> {
            var r = requests.findById(id).orElseThrow();
            r.setStatus(UpgradeRequest.UPGRADED);
            r.setReason(null);
            r.setAttemptCount(r.getAttemptCount() + 1);
            outbox.publish(Topics.ACCOUNT_UPGRADED, r.getAccountNo(), "AccountUpgraded", r.getAccountNo(),
                new AccountUpgraded(r.getId(), r.getAccountNo(), r.getCustomerId(), r.getCustomerName(), r.getEmail(),
                    r.getCurrentType(), r.getTargetType(), r.getBusinessDate(), clock.instant()),
                null);
        });
    }

    private void markFailed(Long id, String reason, boolean alreadyCounted) {
        tx.executeWithoutResult(s -> fail(requests.findById(id).orElseThrow(), reason, alreadyCounted));
    }

    private Result defer(Long id, String reason) {
        return tx.execute(s -> {
            var r = requests.findById(id).orElseThrow();
            r.setAttemptCount(r.getAttemptCount() + 1);
            r.setReason(truncate("Core system unavailable: " + reason));
            if (r.getAttemptCount() >= properties.maxAttempts()) {
                fail(r, "Gave up after " + r.getAttemptCount() + " runs. Last error: " + reason, true);
                return Result.FAILED;
            }
            // Stays attached to this run's id so the run does not pick it up again
            r.setStatus(UpgradeRequest.PENDING);
            return Result.DEFERRED;
        });
    }

    private void fail(UpgradeRequest r, String reason, boolean alreadyCounted) {
        if (!alreadyCounted) {
            r.setAttemptCount(r.getAttemptCount() + 1);
        }
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
}
