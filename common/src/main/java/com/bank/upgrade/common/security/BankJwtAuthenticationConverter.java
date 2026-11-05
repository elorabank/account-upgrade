package com.bank.upgrade.common.security;

import java.util.ArrayList;
import java.util.Collection;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import reactor.core.publisher.Mono;

/**
 * Turns an access token into authorities: each entry of the {@code groups} claim becomes {@code ROLE_<group>}, and
 * each granted scope becomes {@code SCOPE_<scope>}. Scopes are read from the standard {@code scope} claim (a
 * space-separated string, as Keycloak and Auth0 send it) or from {@code scp} (a list, as Okta sends it), so any of
 * those identity providers works without code changes.
 */
public final class BankJwtAuthenticationConverter {

    private BankJwtAuthenticationConverter() {}

    public static Converter<Jwt, Mono<AbstractAuthenticationToken>> reactive() {
        return new ReactiveJwtAuthenticationConverterAdapter(blocking());
    }

    static JwtAuthenticationConverter blocking() {
        var groups = new JwtGrantedAuthoritiesConverter();
        groups.setAuthoritiesClaimName("groups");
        groups.setAuthorityPrefix("ROLE_");

        // No claim name set: Spring reads "scope", then "scp", and splits a space-separated string
        var scopes = new JwtGrantedAuthoritiesConverter();

        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> all = new ArrayList<>(groups.convert(jwt));
            all.addAll(scopes.convert(jwt));
            return all;
        });
        return converter;
    }
}
