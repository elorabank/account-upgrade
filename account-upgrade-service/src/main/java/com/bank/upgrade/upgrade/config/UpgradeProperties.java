package com.bank.upgrade.upgrade.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code bank.upgrade}.
 *
 * @param ingestionUrl                  base URL of the ingestion service, which streams a loaded file's accounts
 * @param batchSize                     requests claimed from the database at a time
 * @param concurrency                   core-system calls in flight at once (agree the number with the core team)
 * @param inlineAttempts                tries per account within one run when the core system is unavailable
 * @param retryBackoff                  wait before the second inline try, doubled for the third
 * @param maxAttempts                   runs an account may fail transiently before it is marked FAILED
 * @param abortAfterConsecutiveFailures stop the run when this many accounts in a row cannot be reached (the core system is probably down)
 */
@ConfigurationProperties("bank.upgrade")
public record UpgradeProperties(
        @DefaultValue("http://localhost:8081") String ingestionUrl,
        @DefaultValue("200") int batchSize,
        @DefaultValue("50") int concurrency,
        @DefaultValue("3") int inlineAttempts,
        @DefaultValue("2s") Duration retryBackoff,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("5") int abortAfterConsecutiveFailures,
        @DefaultValue Rules rules,
        @DefaultValue Simulated simulated) {

    /**
     * The simulated core system ({@code bank.upgrade.gateway=simulated}), for performance tests.
     *
     * @param latency     how long each upgrade call takes
     * @param failureRate share of calls that fail as "unavailable", 0 to 1
     */
    public record Simulated(@DefaultValue("0ms") Duration latency, @DefaultValue("0") double failureRate) {}

    /**
     * Who is eligible and what they are upgraded to.
     *
     * @param targetTypes       current account type to new type; a type that is not listed has no upgrade path
     * @param requiredKycStatus KYC status an account must have
     * @param minBalance        lowest balance that is eligible
     */
    public record Rules(
            Map<String, String> targetTypes,
            @DefaultValue("VERIFIED") String requiredKycStatus,
            @DefaultValue("0") BigDecimal minBalance) {

        public Rules {
            if (targetTypes == null || targetTypes.isEmpty()) {
                targetTypes = Map.of("SAVINGS", "SAVINGS_PLUS", "CURRENT", "CURRENT_PLUS");
            }
        }
    }
}
