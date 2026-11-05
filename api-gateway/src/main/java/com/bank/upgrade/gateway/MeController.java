package com.bank.upgrade.gateway;

import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /me}: who is signed in to the ops console and in which of our groups, so the app can hide buttons a person
 * may not use. This is only for display: every service checks the access token itself.
 */
@RestController
class MeController {

    static final List<String> OUR_GROUPS = List.of("AU_OPS_ADMIN", "AU_OPS_VIEWER", "AU_SUPPORT", "AU_AUDITOR");

    record Me(String name, String email, List<String> roles) {}

    @GetMapping("/me")
    Me me(@AuthenticationPrincipal OidcUser user) {
        Map<String, Object> claims = user.getClaims();
        Object groups = claims.get("groups");
        List<String> roles = groups instanceof List<?> list
            ? list.stream().map(String::valueOf).filter(OUR_GROUPS::contains).toList()
            : List.of();
        String name = user.getFullName() != null ? user.getFullName() : user.getSubject();
        return new Me(name, user.getEmail(), roles);
    }
}
