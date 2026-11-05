package com.bank.upgrade.common.autoconfigure;

import com.bank.upgrade.common.correlation.CorrelationIdWebFilter;
import com.bank.upgrade.common.correlation.CorrelationWebClientFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Correlation id on every incoming request (also at the gateway) and on every WebClient call built from the Boot builder. */
@AutoConfiguration
@ConditionalOnWebApplication(type = Type.REACTIVE)
public class CorrelationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    CorrelationIdWebFilter correlationIdWebFilter() {
        return new CorrelationIdWebFilter();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(WebClientCustomizer.class)
    static class Outgoing {
        @Bean
        WebClientCustomizer correlationWebClientCustomizer() {
            return builder -> builder.filter(CorrelationWebClientFilter.create());
        }
    }
}
