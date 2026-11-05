package com.bank.upgrade.common.events;

/** Data of the {@code NotificationFailed} event on {@link Topics#NOTIFICATION_FAILED}: the welcome email was given up on. */
public record NotificationFailed(long letterId, long upgradeRequestId, String accountNo, String reason, int attempts) {}
