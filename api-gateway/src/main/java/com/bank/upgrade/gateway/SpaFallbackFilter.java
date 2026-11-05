package com.bank.upgrade.gateway;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * The ops console uses addresses like /upgrades or /files/12. When a person opens or reloads one, there is no such
 * file, so the browser gets index.html and the app shows the right page. API, login and actuator paths, and real
 * files (anything with a dot, like /assets/app.js), are left alone. Runs after security, which sees the real path.
 */
@Component
class SpaFallbackFilter implements WebFilter {

    private static final String[] NOT_PAGES = {"/api/", "/oauth2/", "/login", "/logout", "/actuator", "/me", "/internal/"};

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        String path = request.getPath().pathWithinApplication().value();
        boolean page = request.getMethod() == HttpMethod.GET
            && !path.equals("/")
            && !path.contains(".")
            && request.getHeaders().getAccept().stream().anyMatch(MediaType.TEXT_HTML::isCompatibleWith)
            && java.util.Arrays.stream(NOT_PAGES).noneMatch(path::startsWith);
        if (!page) {
            return chain.filter(exchange);
        }
        return chain.filter(exchange.mutate().request(request.mutate().path("/index.html").build()).build());
    }
}
