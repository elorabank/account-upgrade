package com.bank.upgrade.test;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * A transaction manager without a database, for unit tests of code that runs work in a {@code TransactionTemplate}.
 * It counts commits and rollbacks so a test can check which one happened.
 */
public class NoOpTransactionManager implements PlatformTransactionManager {

    public final AtomicInteger commits = new AtomicInteger();
    public final AtomicInteger rollbacks = new AtomicInteger();

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
        commits.incrementAndGet();
    }

    @Override
    public void rollback(TransactionStatus status) {
        rollbacks.incrementAndGet();
    }
}
