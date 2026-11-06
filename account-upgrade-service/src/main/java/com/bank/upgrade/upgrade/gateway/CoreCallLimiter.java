package com.bank.upgrade.upgrade.gateway;

import reactor.core.publisher.Mono;

/** Permission to make one core-system call. Completes when the call may go ahead. */
public interface CoreCallLimiter {

    Mono<Void> acquire();

    /** No shared limit: each copy is held back only by its own {@code concurrency}. */
    static CoreCallLimiter none() {
        return Mono::empty;
    }
}
