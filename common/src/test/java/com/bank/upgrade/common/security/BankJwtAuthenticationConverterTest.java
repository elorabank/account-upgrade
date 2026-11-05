package com.bank.upgrade.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class BankJwtAuthenticationConverterTest {

    private static List<String> authorities(Consumer<Jwt.Builder> claims) {
        var b = Jwt.withTokenValue("t").header("alg", "RS256").subject("s")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.accept(b);
        return BankJwtAuthenticationConverter.blocking().convert(b.build()).getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void keycloakUserGroupsBecomeRoles() {
        assertThat(authorities(b -> b.claim("groups", List.of("AU_OPS_ADMIN", "AU_SUPPORT")).claim("scope", "openid profile email")))
            .contains("ROLE_AU_OPS_ADMIN", "ROLE_AU_SUPPORT", "SCOPE_openid");
    }

    @Test
    void keycloakServiceScopesAreReadFromTheSpaceSeparatedScopeClaim() {
        assertThat(authorities(b -> b.claim("scope", "email profile staging.read")))
            .contains("SCOPE_staging.read").noneMatch(a -> a.startsWith("ROLE_"));
    }

    @Test
    void oktaScopesAreReadFromTheScpList() {
        assertThat(authorities(b -> b.claim("scp", List.of("upgrade.read", "audit.read"))))
            .containsExactlyInAnyOrder("SCOPE_upgrade.read", "SCOPE_audit.read");
    }
}
