package com.bank.upgrade.gateway;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationEntryPoint;
import org.springframework.security.web.server.DelegatingServerAuthenticationEntryPoint;
import org.springframework.security.web.server.DelegatingServerAuthenticationEntryPoint.DelegateEntry;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult;
import reactor.core.publisher.Mono;

/**
 * Two ways in, one rule: the caller must be authenticated. Roles are enforced by each service.
 * <ul>
 *   <li>A request with a bearer token (Postman, other systems) is validated as an Okta JWT and passed on unchanged.</li>
 *   <li>Everything else is a browser: sign in with Okta (authorization code + PKCE), keep the session here,
 *       and relay the user's access token downstream (TokenRelay in the route filters).</li>
 * </ul>
 */
@Configuration
@EnableWebFluxSecurity
class GatewaySecurityConfig {

    private static final String[] OPEN = {"/actuator/health/**", "/actuator/info"};

    @Bean
    @Order(1)
    SecurityWebFilterChain bearerTokenChain(ServerHttpSecurity http) {
        return http
            .securityMatcher(GatewaySecurityConfig::hasBearerToken)
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            .authorizeExchange(a -> a.pathMatchers(OPEN).permitAll().anyExchange().authenticated())
            .oauth2ResourceServer(o -> o.jwt(withDefaults()))
            .build();
    }

    @Bean
    @Order(2)
    SecurityWebFilterChain browserChain(ServerHttpSecurity http, ServerOAuth2AuthorizationRequestResolver pkceResolver) {
        // XHR calls to /api get a 401 they can handle; page navigations are sent to Okta
        var entryPoint = new DelegatingServerAuthenticationEntryPoint(new DelegateEntry(
            new PathPatternParserServerWebExchangeMatcher("/api/**"), new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)));
        entryPoint.setDefaultEntryPoint(new RedirectServerAuthenticationEntryPoint("/oauth2/authorization/okta"));
        return http
            .csrf(c -> c.csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse()))
            .authorizeExchange(a -> a.pathMatchers(OPEN).permitAll().anyExchange().authenticated())
            .oauth2Login(o -> o.authorizationRequestResolver(pkceResolver))
            .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
            .build();
    }

    /** Okta's web app is a confidential client, but PKCE is requested anyway as the design requires. */
    @Bean
    ServerOAuth2AuthorizationRequestResolver pkceResolver(ReactiveClientRegistrationRepository registrations) {
        var resolver = new DefaultServerOAuth2AuthorizationRequestResolver(registrations);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return resolver;
    }

    private static Mono<MatchResult> hasBearerToken(org.springframework.web.server.ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        boolean bearer = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7);
        return bearer ? MatchResult.match() : MatchResult.notMatch();
    }
}
