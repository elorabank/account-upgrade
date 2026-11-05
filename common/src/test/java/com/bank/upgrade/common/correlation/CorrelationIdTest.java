package com.bank.upgrade.common.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class CorrelationIdTest {

    private final CorrelationIdWebFilter filter = new CorrelationIdWebFilter();

    private record Seen(String contextId, String requestHeader) {}

    private Seen run(MockServerWebExchange exchange) {
        var seen = new AtomicReference<Seen>();
        WebFilterChain chain = ex -> Mono.deferContextual(ctx -> {
            seen.set(new Seen(ctx.get(CorrelationId.CONTEXT_KEY), ex.getRequest().getHeaders().getFirst(CorrelationId.HTTP_HEADER)));
            return Mono.empty();
        });
        filter.filter(exchange, chain).block();
        return seen.get();
    }

    @Test
    void callersIdIsKeptAndEchoed() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x").header(CorrelationId.HTTP_HEADER, "abc-123"));
        Seen seen = run(exchange);
        assertThat(seen.contextId()).isEqualTo("abc-123");
        assertThat(seen.requestHeader()).isEqualTo("abc-123");
        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationId.HTTP_HEADER)).isEqualTo("abc-123");
    }

    @Test
    void missingIdIsCreatedAndForwardedOnTheRequest() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));
        Seen seen = run(exchange);
        assertThat(seen.contextId()).isNotBlank().isEqualTo(seen.requestHeader());
        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationId.HTTP_HEADER)).isEqualTo(seen.contextId());
    }

    @Test
    void unsafeIdIsReplaced() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x").header(CorrelationId.HTTP_HEADER, "evil id\nINFO forged log line"));
        assertThat(run(exchange).contextId()).doesNotContain("evil").matches("[A-Za-z0-9-]{36}");
    }

    @Test
    void scopeRestoresThePreviousId() {
        try (var outer = CorrelationId.open("outer")) {
            try (var inner = CorrelationId.open("inner")) {
                assertThat(CorrelationId.current()).isEqualTo("inner");
            }
            assertThat(CorrelationId.current()).isEqualTo("outer");
        }
        assertThat(CorrelationId.current()).isNull();
    }
}
