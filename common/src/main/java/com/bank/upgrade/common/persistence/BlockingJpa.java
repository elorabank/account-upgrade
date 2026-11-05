package com.bank.upgrade.common.persistence;

import com.bank.upgrade.common.correlation.CorrelationId;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The one place reactive code is allowed to touch JPA. JDBC blocks, so the work runs on
 * {@code boundedElastic} (never an event-loop thread), and the correlation id from the Reactor context is
 * put in the logging MDC for the duration, so log lines and outbox events carry it.
 */
public class BlockingJpa {

    private final TransactionTemplate transaction;

    public BlockingJpa(PlatformTransactionManager transactionManager) {
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Runs {@code work} without a transaction (for example a plain read). */
    public <T> Mono<T> call(Supplier<T> work) {
        return onBoundedElastic(work);
    }

    public Mono<Void> run(Runnable work) {
        return this.<Void>onBoundedElastic(() -> {
            work.run();
            return null;
        }).then();
    }

    /** Runs {@code work} in one transaction; any exception rolls everything back. */
    public <T> Mono<T> callInTransaction(Supplier<T> work) {
        return onBoundedElastic(() -> transaction.execute(status -> work.get()));
    }

    public Mono<Void> runInTransaction(Runnable work) {
        return this.<Void>onBoundedElastic(() -> {
            transaction.executeWithoutResult(status -> work.run());
            return null;
        }).then();
    }

    private <T> Mono<T> onBoundedElastic(Supplier<T> work) {
        return Mono.deferContextual(ctx -> Mono.fromCallable(() -> {
            try (var scope = CorrelationId.open(ctx.getOrDefault(CorrelationId.CONTEXT_KEY, null))) {
                return work.get();
            }
        }).subscribeOn(Schedulers.boundedElastic()));
    }
}
