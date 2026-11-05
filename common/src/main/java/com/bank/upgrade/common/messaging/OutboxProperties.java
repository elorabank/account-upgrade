package com.bank.upgrade.common.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("bank.outbox")
public record OutboxProperties(
        @DefaultValue("PT1S") Duration pollInterval,
        @DefaultValue("100") int batchSize,
        @DefaultValue("P7D") Duration retention,
        @DefaultValue("PT30S") Duration sendTimeout) {}
