package com.bank.upgrade.communication.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code bank.communication}.
 *
 * @param from        sender address of the welcome email
 * @param maxAttempts sending attempts before a letter is FAILED
 * @param retryDelay  wait after the first failed attempt, doubled after each further one
 * @param batchSize   letters locked and sent per round
 * @param connections SMTP connections a round sends over in parallel, each carrying an equal share of the batch
 */
@ConfigurationProperties("bank.communication")
public record CommunicationProperties(
        @DefaultValue("no-reply@bank.example") String from,
        @DefaultValue("Example Bank") String fromName,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("1m") Duration retryDelay,
        @DefaultValue("100") int batchSize,
        @DefaultValue("4") int connections) {}
