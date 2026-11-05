package com.bank.upgrade.common.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Retry policy for event handlers. After {@code maxAttempts} the message goes to the dead-letter topic. */
@ConfigurationProperties("bank.consumer")
public record ConsumerProperties(
        @DefaultValue("4") int maxAttempts,
        @DefaultValue("PT0.5S") Duration backoffInitial,
        @DefaultValue("PT10S") Duration backoffMax) {}
