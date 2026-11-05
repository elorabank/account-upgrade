package com.bank.upgrade.common.messaging;

import com.bank.upgrade.common.persistence.outbox.OutboxEvent;
import com.bank.upgrade.common.persistence.outbox.OutboxEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Publishes committed outbox events to Kafka. Each round runs in one database transaction: lock a batch,
 * send it, mark it published, commit. If sending fails, nothing is marked and the next round retries, so an
 * event can be delivered twice after a crash but is never lost. Consumers de-duplicate on the event id.
 * Rounds never overlap, and because locked rows are skipped, running several instances is safe.
 */
public class OutboxPoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
    private static final int MAX_BATCHES_PER_ROUND = 20;

    private final OutboxEventRepository repository;
    private final OutboxSender sender;
    private final TransactionTemplate transaction;
    private final OutboxProperties properties;
    private volatile Disposable polling;
    private volatile Disposable purging;

    public OutboxPoller(OutboxEventRepository repository, OutboxSender sender, TransactionTemplate transaction, OutboxProperties properties) {
        this.repository = repository;
        this.sender = sender;
        this.transaction = transaction;
        this.properties = properties;
    }

    @Override
    public void start() {
        polling = Flux.interval(properties.pollInterval())
            .onBackpressureDrop()
            .concatMap(tick -> guarded("publish", this::drain), 1)
            .subscribe();
        purging = Flux.interval(Duration.ofMinutes(10))
            .onBackpressureDrop()
            .concatMap(tick -> guarded("purge", this::purge), 1)
            .subscribe();
    }

    private Mono<Void> guarded(String what, Runnable work) {
        return Mono.fromRunnable(work)
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(e -> {
                log.warn("Outbox {} failed, will retry: {}", what, e.toString());
                return Mono.empty();
            })
            .then();
    }

    /** Publishes until nothing is left, or a safety limit of batches per round is reached. */
    void drain() {
        for (int i = 0; i < MAX_BATCHES_PER_ROUND; i++) {
            int published = publishOneBatch();
            if (published < properties.batchSize()) {
                return;
            }
        }
    }

    int publishOneBatch() {
        Integer count = transaction.execute(status -> {
            List<OutboxEvent> batch = repository.lockNextBatch(properties.batchSize());
            if (batch.isEmpty()) {
                return 0;
            }
            sender.send(batch);
            Instant now = Instant.now();
            for (OutboxEvent event : batch) {
                event.setStatus(OutboxEvent.PUBLISHED);
                event.setPublishedAt(now);
            }
            return batch.size();
        });
        return count == null ? 0 : count;
    }

    void purge() {
        Instant before = Instant.now().minus(properties.retention());
        Integer removed = transaction.execute(status -> repository.deletePublishedBefore(before));
        if (removed != null && removed > 0) {
            log.info("Removed {} published outbox events older than {}", removed, properties.retention());
        }
    }

    @Override
    public void stop() {
        if (polling != null) {
            polling.dispose();
        }
        if (purging != null) {
            purging.dispose();
        }
        polling = null;
        purging = null;
    }

    @Override
    public boolean isRunning() {
        return polling != null;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100; // start late, stop early
    }
}
