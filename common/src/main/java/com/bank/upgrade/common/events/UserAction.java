package com.bank.upgrade.common.events;

/**
 * Data of a {@code UserAction} event on {@link Topics#USER_ACTION}: a person (or a service on their behalf) did
 * something by hand. Who did it is the envelope's {@code actor}.
 *
 * @param action     for example {@code RESEND_WELCOME_EMAIL}
 * @param targetType what was acted on, for example {@code WELCOME_LETTER}
 */
public record UserAction(String action, String targetType, String targetId, String accountNo) {}
