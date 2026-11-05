package com.bank.upgrade.common.events;

import java.time.LocalDate;

/** Data of the {@code UpgradeFailed} event on {@link Topics#UPGRADE_FAILED}: the upgrade was given up on and needs a person. */
public record UpgradeFailed(
        long requestId,
        String accountNo,
        String customerId,
        String fromType,
        String toType,
        LocalDate businessDate,
        String reason,
        int attempts) {}
