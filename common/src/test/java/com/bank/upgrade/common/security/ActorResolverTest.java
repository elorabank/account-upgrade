package com.bank.upgrade.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import reactor.test.StepVerifier;

class ActorResolverTest {

    private static JwtAuthenticationToken token(java.util.function.Consumer<Jwt.Builder> customize) {
        var b = Jwt.withTokenValue("t").header("alg", "RS256").subject("sub-1")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        customize.accept(b);
        return new JwtAuthenticationToken(b.build());
    }

    @Test
    void signedInUserIsNamedByEmail() {
        assertThat(ActorResolver.of(token(b -> b.claim("uid", "00u1").claim("email", "pat@bank.test"))))
            .isEqualTo("pat@bank.test");
    }

    @Test
    void signedInUserWithoutEmailFallsBackToSubject() {
        assertThat(ActorResolver.of(token(b -> b.claim("uid", "00u1")))).isEqualTo("sub-1");
    }

    @Test
    void serviceTokenIsASystemActor() {
        assertThat(ActorResolver.of(token(b -> b.claim("cid", "svc-upgrade")))).isEqualTo("system:svc-upgrade");
    }

    @Test
    void keycloakUserIsNamedByEmailOrUserName() {
        assertThat(ActorResolver.of(token(b -> b.claim("email", "olivia@bank.test").claim("preferred_username", "olivia.ops")
            .claim("azp", "api-gateway")))).isEqualTo("olivia@bank.test");
        assertThat(ActorResolver.of(token(b -> b.claim("preferred_username", "olivia.ops")))).isEqualTo("olivia.ops");
    }

    @Test
    void keycloakServiceAccountIsASystemActor() {
        assertThat(ActorResolver.of(token(b -> b.claim("client_id", "account-upgrade-service")
            .claim("preferred_username", "service-account-account-upgrade-service")))).isEqualTo("system:account-upgrade-service");
        // older Keycloak versions: only the service-account user name
        assertThat(ActorResolver.of(token(b -> b.claim("preferred_username", "service-account-reporting"))))
            .isEqualTo("system:reporting");
    }

    @Test
    void oktaUserTokenAlsoCarriesTheClientButIsStillThePerson() {
        assertThat(ActorResolver.of(token(b -> b.claim("uid", "00u1").claim("cid", "gateway").claim("email", "pat@bank.test"))))
            .isEqualTo("pat@bank.test");
    }

    @Test
    void schedulerAndMissingCallerAreSystemActors() {
        assertThat(ActorResolver.scheduler("upgrade-scheduler")).isEqualTo("system:upgrade-scheduler");
        assertThat(ActorResolver.of(null)).isEqualTo("system:unknown");
    }

    @Test
    void currentReadsTheReactiveSecurityContext() {
        var auth = token(b -> b.claim("uid", "00u1").claim("email", "pat@bank.test"));
        StepVerifier.create(ActorResolver.current()
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(reactor.core.publisher.Mono.just(new SecurityContextImpl(auth)))))
            .expectNext("pat@bank.test").verifyComplete();
        StepVerifier.create(ActorResolver.current()).verifyComplete();
    }
}
