package com.bank.upgrade.upgrade.gateway;

/**
 * The call that really changes the account in the bank's core system. It must be idempotent on
 * {@link UpgradeCommand#idempotencyKey()}: after a crash the same upgrade is sent again.
 * Throw {@link GatewayUnavailableException} for a temporary problem and {@link GatewayRejectedException} for a refusal.
 */
public interface AccountUpgradeGateway {
    void upgrade(UpgradeCommand command);
}
