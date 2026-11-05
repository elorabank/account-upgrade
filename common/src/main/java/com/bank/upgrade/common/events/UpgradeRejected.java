package com.bank.upgrade.common.events;

import java.time.LocalDate;

/** Data of the {@code UpgradeRejected} event on {@link Topics#UPGRADE_REJECTED}: the account did not meet the upgrade rules. */
public record UpgradeRejected(
        long requestId,
        String accountNo,
        String customerId,
        String currentType,
        LocalDate businessDate,
        String reason) {}
