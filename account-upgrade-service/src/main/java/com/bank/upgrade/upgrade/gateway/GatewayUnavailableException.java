package com.bank.upgrade.upgrade.gateway;

/** The core system could not be reached or answered with a temporary error. Trying again later may work. */
public class GatewayUnavailableException extends RuntimeException {
    public GatewayUnavailableException(String message) {
        super(message);
    }

    public GatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
