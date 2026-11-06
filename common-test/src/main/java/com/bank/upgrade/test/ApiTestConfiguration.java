package com.bank.upgrade.test;

import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.transaction.PlatformTransactionManager;

/** The pieces {@link ApiTest} adds: a token decoder for {@link TestJwt}'s keys and a transaction manager without a database. */
@TestConfiguration(proxyBeanMethods = false)
public class ApiTestConfiguration {

    /** Checks signature, expiry, issuer and audience, like the decoder Spring Boot builds from the issuer settings. */
    @Bean
    ReactiveJwtDecoder testJwtDecoder() {
        var decoder = NimbusReactiveJwtDecoder.withPublicKey(TestJwt.shared().publicKey()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(TestJwt.ISSUER),
            new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(TestJwt.AUDIENCE))));
        return decoder;
    }

    @Bean
    PlatformTransactionManager transactionManager() {
        return new NoOpTransactionManager();
    }
}
