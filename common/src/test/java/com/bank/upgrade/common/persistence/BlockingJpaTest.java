package com.bank.upgrade.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.upgrade.common.correlation.CorrelationId;
import com.bank.upgrade.test.NoOpTransactionManager;
import org.junit.jupiter.api.Test;
import reactor.util.context.Context;

/** Blocking repository work runs on a worker thread, under the request's correlation id, in a transaction. */
class BlockingJpaTest {

    final NoOpTransactionManager tx = new NoOpTransactionManager();
    final BlockingJpa jpa = new BlockingJpa(tx);

    @Test
    void workRunsOffTheEventLoopWithTheCorrelationIdFromTheReactiveContext() {
        String[] seen = new String[2];
        jpa.callInTransaction(() -> {
                seen[0] = Thread.currentThread().getName();
                seen[1] = CorrelationId.current();
                return "done";
            })
            .contextWrite(Context.of(CorrelationId.CONTEXT_KEY, "req-5"))
            .block();

        assertThat(seen[0]).startsWith("boundedElastic");
        assertThat(seen[1]).isEqualTo("req-5");
        assertThat(tx.commits).hasValue(1);
    }

    @Test
    void aFailureRollsBackAndReachesTheCaller() {
        assertThatThrownBy(() -> jpa.runInTransaction(() -> {
            throw new IllegalStateException("constraint violated");
        }).block()).hasMessageContaining("constraint violated");

        assertThat(tx.rollbacks).hasValue(1);
        assertThat(tx.commits).hasValue(0);
    }
}
