package com.bank.upgrade.upgrade.gateway;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Stand-in until the core banking API is known. Accepts every upgrade; for performance tests it can answer slowly
 * ({@code latency}, like a real remote call, without holding a thread) and sometimes be unavailable
 * ({@code failureRate}, 0 to 1). Replace it with a real adapter bean.
 */
public class SimulatedAccountUpgradeGateway implements AccountUpgradeGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedAccountUpgradeGateway.class);

    private final Duration latency;
    private final double failureRate;

    public SimulatedAccountUpgradeGateway(Duration latency, double failureRate) {
        this.latency = latency;
        this.failureRate = failureRate;
    }

    @Override
    public Mono<Void> upgrade(UpgradeCommand command) {
        Mono<Void> answer = Mono.defer(() -> {
            if (failureRate > 0 && ThreadLocalRandom.current().nextDouble() < failureRate) {
                return Mono.error(new GatewayUnavailableException("SIMULATED core system unavailable"));
            }
            log.debug("SIMULATED core system: account {} {} -> {} (key {})",
                command.accountNo(), command.fromType(), command.toType(), command.idempotencyKey());
            return Mono.empty();
        });
        return latency.isZero() ? answer : Mono.delay(latency).then(answer);
    }
}
