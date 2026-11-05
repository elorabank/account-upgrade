package com.bank.upgrade.common.messaging;

import com.bank.upgrade.common.persistence.outbox.OutboxEvent;
import java.util.List;

/** Delivers a batch of outbox events, in order. Throws if any was not accepted; the batch is then retried. */
public interface OutboxSender {
    void send(List<OutboxEvent> batch);
}
