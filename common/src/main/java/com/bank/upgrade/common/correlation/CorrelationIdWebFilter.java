package com.bank.upgrade.common.correlation;

import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Takes the caller's {@code X-Correlation-Id} (or makes one), puts it on the request, response and Reactor context. */
public class CorrelationIdWebFilter implements WebFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String id = CorrelationId.sanitize(exchange.getRequest().getHeaders().getFirst(CorrelationId.HTTP_HEADER));
        // the request header is also what a gateway forwards to the services behind it
        ServerWebExchange withId = exchange.mutate()
            .request(r -> r.headers(h -> h.set(CorrelationId.HTTP_HEADER, id)))
            .build();
        withId.getResponse().getHeaders().set(CorrelationId.HTTP_HEADER, id);
        return chain.filter(withId).contextWrite(ctx -> ctx.put(CorrelationId.CONTEXT_KEY, id));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
