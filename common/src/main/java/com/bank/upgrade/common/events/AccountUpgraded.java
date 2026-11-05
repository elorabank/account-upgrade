package com.bank.upgrade.common.events;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Data of the {@code AccountUpgraded} event on {@link Topics#ACCOUNT_UPGRADED}. It carries what the letter and
 * email need, so the letter service does not have to call back. {@code requestId} identifies the upgrade request
 * in the upgrade service.
 */
public record AccountUpgraded(
        long requestId,
        String accountNo,
        String customerId,
        String customerName,
        String email,
        String fromType,
        String toType,
        LocalDate businessDate,
        Instant upgradedAt) {}
