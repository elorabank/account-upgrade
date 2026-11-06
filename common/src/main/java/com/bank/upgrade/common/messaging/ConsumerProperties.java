package com.bank.upgrade.common.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How events are consumed. Up to {@code batchSize} events of one partition are handled in one transaction, waiting at
 * most {@code batchWait} to fill a batch. A batch that fails is handled again one event at a time, each with up to
 * {@code maxAttempts} tries, after which the event goes to the dead-letter topic.
 */
@ConfigurationProperties("bank.consumer")
public record ConsumerProperties(
        @DefaultValue("4") int maxAttempts,
        @DefaultValue("PT0.5S") Duration backoffInitial,
        @DefaultValue("PT10S") Duration backoffMax,
        @DefaultValue("100") int batchSize,
        @DefaultValue("PT0.05S") Duration batchWait) {}
