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
                return person(jwt);
            }
            // A service's own token: Keycloak names the client in "client_id" (older versions "clientId") and the
            // user as "service-account-<client>"; Okta names it in "cid"
            String client = first(jwt, "client_id", "clientId", "cid");
            String username = jwt.getClaimAsString("preferred_username");
            if (client == null && username != null && username.startsWith(SERVICE_ACCOUNT)) {
                client = username.substring(SERVICE_ACCOUNT.length());
            }
            if (client != null) {
                return "system:" + client;
            }
            if (jwt.hasClaim("email") || username != null) {
                return person(jwt);
            }
            return "system:" + jwt.getSubject();
        }
        return authentication != null ? authentication.getName() : "system:unknown";
    }

    private static final String SERVICE_ACCOUNT = "service-account-";

    /** A person: their email, else their user name, else the token subject. */
    private static String person(Jwt jwt) {
        String name = first(jwt, "email", "preferred_username");
        return name != null ? name : jwt.getSubject();
    }

    private static String first(Jwt jwt, String... claims) {
        for (String claim : claims) {
            String value = jwt.getClaimAsString(claim);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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
