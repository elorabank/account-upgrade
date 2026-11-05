package com.bank.upgrade.common.autoconfigure;

import com.bank.upgrade.common.security.ApiAccessRules;
import com.bank.upgrade.common.security.OktaJwtAuthenticationConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;

/**
 * Stateless Okta JWT protection for a service. On by default wherever the security libraries are
 * present, so a new service is secure from the start; set {@code bank.security.resource-server.enabled=false}
 * to turn it off. It backs off if the app defines its own chain (the gateway does).
 * Runs before Spring Boot's own default chains so only one chain exists.
 */
@AutoConfiguration(beforeName = {
    "org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration",
    "org.springframework.boot.autoconfigure.security.oauth2.resource.reactive.ReactiveOAuth2ResourceServerAutoConfiguration",
    "org.springframework.boot.actuate.autoconfigure.security.reactive.ReactiveManagementWebSecurityAutoConfiguration",
    // a service that also calls others (OAuth2 client) must not get Boot's login chain instead of this one
    "org.springframework.boot.autoconfigure.security.oauth2.client.reactive.ReactiveOAuth2ClientAutoConfiguration",
    "org.springframework.boot.autoconfigure.security.oauth2.client.reactive.ReactiveOAuth2ClientWebSecurityAutoConfiguration"})
@ConditionalOnWebApplication(type = Type.REACTIVE)
@ConditionalOnClass({ServerHttpSecurity.class, ReactiveJwtDecoder.class})
@ConditionalOnProperty(prefix = "bank.security.resource-server", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ResourceServerSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SecurityWebFilterChain.class)
    SecurityWebFilterChain apiSecurityFilterChain(ServerHttpSecurity http) {
        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)                       // bearer tokens only, no cookies
            .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            .requestCache(c -> c.requestCache(NoOpServerRequestCache.getInstance()))
            .authorizeExchange(ApiAccessRules::apply)
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(OktaJwtAuthenticationConverter.reactive())))
            .build();
    }
}
