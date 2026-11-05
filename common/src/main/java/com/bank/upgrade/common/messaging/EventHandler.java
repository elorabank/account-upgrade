package com.bank.upgrade.common.messaging;

import com.bank.upgrade.common.events.EventEnvelope;

/**
 * Business logic for one topic. It runs inside the same database transaction that records the event as
 * processed, so an outbox event it publishes is saved atomically with its own changes. An exception makes
 * the whole thing roll back and retry; throw {@link NonRetryableEventException} for an event that can never succeed.
 */
@FunctionalInterface
public interface EventHandler {
    void handle(EventEnvelope event) throws Exception;
}
