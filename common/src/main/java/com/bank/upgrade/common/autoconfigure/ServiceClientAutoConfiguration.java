package com.bank.upgrade.common.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServerOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Service-to-service calls with an Okta client-credentials token. Turn on with
 * {@code bank.security.client.registration-id=<name of the spring.security.oauth2.client registration>}, then add
 * the {@code serviceTokenFilter} bean to a WebClient: {@code builder.filter(serviceTokenFilter).build()}.
 * Tokens are cached until they expire, and no signed-in user is needed, so schedulers can use it.
 */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.security.oauth2.client.reactive.ReactiveOAuth2ClientAutoConfiguration")
@ConditionalOnClass({WebClient.class, ReactiveClientRegistrationRepository.class})
@ConditionalOnProperty(prefix = "bank.security.client", name = "registration-id")
@ConditionalOnBean(ReactiveClientRegistrationRepository.class)
public class ServiceClientAutoConfiguration {

    @Bean
    ReactiveOAuth2AuthorizedClientManager serviceClientManager(ReactiveClientRegistrationRepository registrations) {
        var manager = new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(
            registrations, new InMemoryReactiveOAuth2AuthorizedClientService(registrations));
        manager.setAuthorizedClientProvider(ReactiveOAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }

    @Bean
    ExchangeFilterFunction serviceTokenFilter(
            ReactiveOAuth2AuthorizedClientManager serviceClientManager,
            org.springframework.core.env.Environment env) {
        var filter = new ServerOAuth2AuthorizedClientExchangeFilterFunction(serviceClientManager);
        filter.setDefaultClientRegistrationId(env.getRequiredProperty("bank.security.client.registration-id"));
        return filter;
    }
}
