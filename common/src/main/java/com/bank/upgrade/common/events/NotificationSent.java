package com.bank.upgrade.common.events;

import java.time.Instant;

/** Data of the {@code NotificationSent} event on {@link Topics#NOTIFICATION_SENT}: the welcome email was accepted by the mail server. */
public record NotificationSent(long letterId, long upgradeRequestId, String accountNo, int attempts, Instant sentAt) {}
