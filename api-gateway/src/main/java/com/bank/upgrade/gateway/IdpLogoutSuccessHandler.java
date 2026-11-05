package com.bank.upgrade.gateway;

import java.net.URI;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.server.DefaultServerRedirectStrategy;
import org.springframework.security.web.server.ServerRedirectStrategy;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

/**
 * After the gateway session ends, also end the session at the identity provider (OIDC RP-initiated logout), so the
 * next visit asks for a login instead of signing the same person straight back in. The ID token tells the provider
 * whose session to end and lets it skip its "are you sure" page; it then sends the browser back to the start page.
 * Without a logout address configured, only the gateway session ends.
 */
class IdpLogoutSuccessHandler implements ServerLogoutSuccessHandler {

    private final String idpLogoutUri;
    private final ServerRedirectStrategy redirect = new DefaultServerRedirectStrategy();

    IdpLogoutSuccessHandler(String idpLogoutUri) {
        this.idpLogoutUri = idpLogoutUri;
    }

    @Override
    public Mono<Void> onLogoutSuccess(WebFilterExchange exchange, Authentication authentication) {
        URI request = exchange.getExchange().getRequest().getURI();
        String home = UriComponentsBuilder.newInstance().scheme(request.getScheme()).host(request.getHost())
            .port(request.getPort()).path("/").toUriString();
        if (idpLogoutUri == null || idpLogoutUri.isBlank()
                || authentication == null || !(authentication.getPrincipal() instanceof OidcUser user)) {
            return redirect.sendRedirect(exchange.getExchange(), URI.create(home));
        }
        URI target = UriComponentsBuilder.fromUriString(idpLogoutUri)
            .queryParam("id_token_hint", user.getIdToken().getTokenValue())
            .queryParam("post_logout_redirect_uri", home)
            .encode().build().toUri();
        return redirect.sendRedirect(exchange.getExchange(), target);
    }
}
