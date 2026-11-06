package com.bank.upgrade.upgrade.gateway;

import reactor.core.publisher.Mono;

/**
 * The call that really changes the account in the bank's core system. It is non-blocking (a real adapter uses
 * WebClient), so one service copy can have many calls in flight without a thread waiting on each.
 * <p>
 * It must be idempotent on {@link UpgradeCommand#idempotencyKey()}: after a crash the same upgrade is sent again.
 * The returned Mono fails with {@link GatewayUnavailableException} for a temporary problem and with
 * {@link GatewayRejectedException} for a refusal; subscribing again repeats the call (that is how retries work).
 */
public interface AccountUpgradeGateway {
    Mono<Void> upgrade(UpgradeCommand command);
}
