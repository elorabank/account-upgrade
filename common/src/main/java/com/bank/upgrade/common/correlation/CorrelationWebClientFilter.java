package com.bank.upgrade.common.correlation;

import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

/** Copies the current correlation id onto every outgoing WebClient request. */
public final class CorrelationWebClientFilter {

    private CorrelationWebClientFilter() {}

    public static ExchangeFilterFunction create() {
        return (request, next) -> Mono.deferContextual(ctx -> {
            String id = ctx.getOrDefault(CorrelationId.CONTEXT_KEY, null);
            ClientRequest outgoing = id == null
                ? request
                : ClientRequest.from(request).headers(h -> h.set(CorrelationId.HTTP_HEADER, id)).build();
            return next.exchange(outgoing);
        });
    }
}
