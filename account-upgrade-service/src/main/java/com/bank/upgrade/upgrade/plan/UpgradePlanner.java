package com.bank.upgrade.upgrade.plan;

import com.bank.upgrade.common.api.StagedAccount;
import com.bank.upgrade.common.correlation.CorrelationId;
import com.bank.upgrade.common.events.FileLoaded;
import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.events.UpgradeRejected;
import com.bank.upgrade.common.messaging.OutboxService;
import com.bank.upgrade.upgrade.domain.UpgradeRequest;
import com.bank.upgrade.upgrade.domain.UpgradeRequestRepository;
import com.bank.upgrade.upgrade.staging.StagingSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Turns the accounts of a loaded file into upgrade requests: PENDING for eligible accounts, REJECTED (with the reason
 * and an {@code UpgradeRejected} event) for the others.
 * <p>
 * The accounts arrive as a stream from the ingestion service and are saved in chunks, one short transaction each.
 * {@code concatMap} handles one chunk at a time and only then asks for more, so the HTTP stream, and behind it the
 * ingestion service's database reads, go at the speed this service can save.
 * <p>
 * If it stops half way, the event is delivered again and planning starts over: accounts already queued for the
 * business day are skipped, so nothing is queued twice.
 */
public class UpgradePlanner {

    private static final Logger log = LoggerFactory.getLogger(UpgradePlanner.class);
    private static final int CHUNK = 500;

    public record Plan(int pending, int rejected, int alreadyQueued) {
        static final Plan EMPTY = new Plan(0, 0, 0);

        Plan plus(Plan other) {
            return new Plan(pending + other.pending, rejected + other.rejected, alreadyQueued + other.alreadyQueued);
        }
    }

    private final StagingSource staging;
    private final EligibilityRules rules;
    private final UpgradeRequestRepository requests;
    private final OutboxService outbox;
    private final TransactionTemplate tx;

    public UpgradePlanner(StagingSource staging, EligibilityRules rules, UpgradeRequestRepository requests,
            OutboxService outbox, PlatformTransactionManager transactionManager) {
        this.staging = staging;
        this.rules = rules;
        this.requests = requests;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Blocks until the whole file is planned. Called from the event consumer, which runs on a thread that may block. */
    public Plan plan(FileLoaded file, String correlationId) {
        // Only this pipeline touches the set, one chunk after another
        Set<String> queued = new HashSet<>(requests.findAccountNosByBusinessDate(file.businessDate()));
        Plan plan = staging.accounts(file.fileId())
            .buffer(CHUNK)
            .concatMap(chunk -> Mono.fromCallable(() -> saveChunk(file, chunk, queued, correlationId))
                .subscribeOn(Schedulers.boundedElastic()))
            .reduce(Plan.EMPTY, Plan::plus)
            .block();
        int found = plan.pending() + plan.rejected() + plan.alreadyQueued();
        if (found != file.loadedRows()) {
            log.warn("File {} announced {} loaded rows but {} were received", file.fileId(), file.loadedRows(), found);
        }
        log.info("File {} planned: {} to upgrade, {} rejected, {} already queued",
            file.fileId(), plan.pending(), plan.rejected(), plan.alreadyQueued());
        return plan;
    }

    private Plan saveChunk(FileLoaded file, List<StagedAccount> chunk, Set<String> queued, String correlationId) {
        // This runs on another thread: the event's correlation id has to be set again for the outbox and the logs
        try (var scope = CorrelationId.open(correlationId)) {
            return tx.execute(status -> {
                int skipped = 0;
                List<UpgradeRequest> batch = new ArrayList<>(chunk.size());
                for (StagedAccount account : chunk) {
                    if (!queued.add(account.accountNo())) {
                        skipped++;
                        continue;
                    }
                    batch.add(request(file, account, correlationId));
                }
                requests.saveAll(batch);
                int rejected = 0;
                for (UpgradeRequest r : batch) {
                    if (UpgradeRequest.REJECTED.equals(r.getStatus())) {
                        rejected++;
                        outbox.publish(Topics.UPGRADE_REJECTED, r.getAccountNo(), "UpgradeRejected", r.getAccountNo(),
                            new UpgradeRejected(r.getId(), r.getAccountNo(), r.getCustomerId(), r.getCurrentType(),
                                r.getBusinessDate(), r.getReason()),
                            null);
                    }
                }
                return new Plan(batch.size() - rejected, rejected, skipped);
            });
        }
    }

    private UpgradeRequest request(FileLoaded file, StagedAccount account, String correlationId) {
        var decision = rules.evaluate(account);
        var request = new UpgradeRequest();
        request.setAccountNo(account.accountNo());
        request.setCustomerId(account.customerId());
        request.setCustomerName(account.customerName());
        request.setEmail(account.email());
        request.setCurrentType(account.currentType());
        request.setTargetType(decision.targetType());
        request.setBusinessDate(file.businessDate());
        request.setSourceFileId(file.fileId());
        request.setCorrelationId(correlationId);
        if (decision.eligible()) {
            request.setStatus(UpgradeRequest.PENDING);
        } else {
            request.setStatus(UpgradeRequest.REJECTED);
            request.setReason(decision.reason());
        }
        return request;
    }
}
