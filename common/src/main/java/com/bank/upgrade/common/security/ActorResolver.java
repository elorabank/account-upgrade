package com.bank.upgrade.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

/**
 * Names who is responsible for an action, for the audit trail: the user's email (or subject) for a
 * person, {@code system:<client id>} for a service token, and {@code system:<name>} for a scheduler.
 */
public final class ActorResolver {

    private ActorResolver() {}

    public static String of(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            // Okta puts "uid" only in tokens issued for a signed-in user
            if (jwt.hasClaim("uid")) {
                String email = jwt.getClaimAsString("email");
                return email != null ? email : jwt.getSubject();
            }
            String client = jwt.getClaimAsString("cid");
            return "system:" + (client != null ? client : jwt.getSubject());
        }
        return authentication != null ? authentication.getName() : "system:unknown";
    }

    /** The actor of the current request; empty when there is no authenticated caller. */
    public static Mono<String> current() {
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .map(ActorResolver::of);
    }

    public static String scheduler(String name) {
        return "system:" + name;
    }
}
