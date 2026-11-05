package com.bank.upgrade.common.messaging;

/** The event is bad in a way retrying cannot fix. It goes straight to the dead-letter topic. */
public class NonRetryableEventException extends RuntimeException {

    public NonRetryableEventException(String message) {
        super(message);
    }

    public NonRetryableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
