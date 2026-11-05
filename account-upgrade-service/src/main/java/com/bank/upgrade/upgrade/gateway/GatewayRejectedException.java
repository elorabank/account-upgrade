package com.bank.upgrade.upgrade.gateway;

/** The core system refused this upgrade (account closed, blocked, already a different type). Trying again will not help. */
public class GatewayRejectedException extends RuntimeException {
    public GatewayRejectedException(String message) {
        super(message);
    }
}
