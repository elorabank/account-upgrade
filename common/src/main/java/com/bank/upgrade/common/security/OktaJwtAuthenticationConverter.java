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
 * Turns an Okta access token into authorities: each entry of the {@code groups} claim becomes
 * {@code ROLE_<group>} and each {@code scp} scope becomes {@code SCOPE_<scope>}.
 */
public final class OktaJwtAuthenticationConverter {

    private OktaJwtAuthenticationConverter() {}

    public static Converter<Jwt, Mono<AbstractAuthenticationToken>> reactive() {
        return new ReactiveJwtAuthenticationConverterAdapter(blocking());
    }

    static JwtAuthenticationConverter blocking() {
        var groups = new JwtGrantedAuthoritiesConverter();
        groups.setAuthoritiesClaimName("groups");
        groups.setAuthorityPrefix("ROLE_");

        var scopes = new JwtGrantedAuthoritiesConverter();
        scopes.setAuthoritiesClaimName("scp");

        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> all = new ArrayList<>(groups.convert(jwt));
            all.addAll(scopes.convert(jwt));
            return all;
        });
        return converter;
    }
}
