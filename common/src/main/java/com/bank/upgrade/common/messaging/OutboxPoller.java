package com.bank.upgrade.common.messaging;

import com.bank.upgrade.common.persistence.outbox.OutboxEvent;
import com.bank.upgrade.common.persistence.outbox.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
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
 * <p>
 * Metrics: {@code bank.outbox.published} (by topic), and, measured every 5 seconds, {@code bank.outbox.waiting}
 * (events not yet published) and {@code bank.outbox.oldest.age} (seconds the oldest of them has waited).
 */
public class OutboxPoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
    private static final int MAX_BATCHES_PER_ROUND = 20;

    private final OutboxEventRepository repository;
    private final OutboxSender sender;
    private final TransactionTemplate transaction;
    private final OutboxProperties properties;
    private final MeterRegistry meters;
    private final AtomicLong waiting = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();
    private volatile Disposable polling;
    private volatile Disposable purging;
    private volatile Disposable measuring;

    public OutboxPoller(OutboxEventRepository repository, OutboxSender sender, TransactionTemplate transaction,
            OutboxProperties properties, MeterRegistry meters) {
        this.repository = repository;
        this.sender = sender;
        this.transaction = transaction;
        this.properties = properties;
        this.meters = meters;
        Gauge.builder("bank.outbox.waiting", waiting, AtomicLong::get)
            .description("Outbox events written but not yet published to Kafka").register(meters);
        Gauge.builder("bank.outbox.oldest.age", oldestAgeSeconds, AtomicLong::get).baseUnit("seconds")
            .description("How long the oldest unpublished outbox event has waited").register(meters);
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
        measuring = Flux.interval(Duration.ZERO, Duration.ofSeconds(5))
            .onBackpressureDrop()
            .concatMap(tick -> guarded("measure", this::measure), 1)
            .subscribe();
    }

    /** Two index lookups: how many events wait, and since when. */
    void measure() {
        waiting.set(repository.countByStatus(OutboxEvent.NEW));
        Instant oldest = repository.findOldestNewCreatedAt();
        oldestAgeSeconds.set(oldest == null ? 0 : Math.max(0, Duration.between(oldest, Instant.now()).toSeconds()));
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
            countPublished(batch);
            Instant now = Instant.now();
            for (OutboxEvent event : batch) {
                event.setStatus(OutboxEvent.PUBLISHED);
                event.setPublishedAt(now);
            }
            return batch.size();
        });
        return count == null ? 0 : count;
    }

    private void countPublished(List<OutboxEvent> batch) {
        Map<String, Long> byTopic = batch.stream().collect(Collectors.groupingBy(OutboxEvent::getTopic, Collectors.counting()));
        byTopic.forEach((topic, n) -> Counter.builder("bank.outbox.published")
            .description("Outbox events sent to Kafka (counted when sent; a failed commit sends them again)")
            .tag("topic", topic).register(meters).increment(n));
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
        if (measuring != null) {
            measuring.dispose();
        }
        polling = null;
        purging = null;
        measuring = null;
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
