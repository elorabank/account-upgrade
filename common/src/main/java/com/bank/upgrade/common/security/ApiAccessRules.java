package com.bank.upgrade.common.security;

import static com.bank.upgrade.common.security.Roles.*;

import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity.AuthorizeExchangeSpec;

/**
 * Who may call what. The same rules apply in every service, so a route is protected no matter which
 * service ends up hosting it. A scope (service-to-service token) is accepted wherever a role is.
 * Any {@code /api/**} call that no rule names is refused: a new endpoint needs a rule here first.
 * {@code /internal/**} is for other services only: a scope, never a person's role, and the gateway does not route it.
 */
public final class ApiAccessRules {

    private ApiAccessRules() {}

    public static void apply(AuthorizeExchangeSpec a) {
        a.pathMatchers("/actuator/health/**", "/actuator/info").permitAll()

            // manual re-run, retry
            .pathMatchers(HttpMethod.POST, "/api/jobs/**").hasAnyAuthority(OPS_ADMIN, SCOPE_UPGRADE_ADMIN)
            .pathMatchers(HttpMethod.POST, "/api/upgrades/*/retry").hasAnyAuthority(OPS_ADMIN, SCOPE_UPGRADE_ADMIN)

            // resend an email
            .pathMatchers(HttpMethod.POST, "/api/notifications/*/resend")
                .hasAnyAuthority(OPS_ADMIN, SUPPORT, SCOPE_NOTIFICATION_RESEND)

            // read access
            .pathMatchers(HttpMethod.GET, "/api/files/**", "/api/upgrades/**")
                .hasAnyAuthority(OPS_ADMIN, OPS_VIEWER, SUPPORT, AUDITOR, SCOPE_UPGRADE_READ)
            .pathMatchers(HttpMethod.GET, "/api/audit/**").hasAnyAuthority(OPS_ADMIN, AUDITOR, SCOPE_AUDIT_READ)

            // everything else under /api is closed until a rule is added above
            .pathMatchers("/api/**").denyAll()

            // service to service
            .pathMatchers(HttpMethod.GET, "/internal/files/*/accounts").hasAuthority(SCOPE_STAGING_READ)
            .pathMatchers("/internal/**").denyAll()

            // other paths (for example /actuator/prometheus) need any valid token
            .anyExchange().authenticated();
    }
}
