package com.bank.upgrade.upgrade.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Stand-in until the core banking API is known: accepts every upgrade. Replace it with a real adapter bean. */
public class SimulatedAccountUpgradeGateway implements AccountUpgradeGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedAccountUpgradeGateway.class);

    @Override
    public void upgrade(UpgradeCommand command) {
        log.info("SIMULATED core system: account {} {} -> {} (key {})",
            command.accountNo(), command.fromType(), command.toType(), command.idempotencyKey());
    }
}
