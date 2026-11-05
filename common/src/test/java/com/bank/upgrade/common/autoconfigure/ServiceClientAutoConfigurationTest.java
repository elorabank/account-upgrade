package com.bank.upgrade.common.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

class ServiceClientAutoConfigurationTest {

    HttpServer okta;
    HttpServer api;
    final AtomicInteger tokenCalls = new AtomicInteger();
    final AtomicReference<String> tokenRequestBody = new AtomicReference<>();
    final AtomicReference<String> authHeaderSeenByApi = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        okta = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        okta.createContext("/token", ex -> {
            tokenCalls.incrementAndGet();
            tokenRequestBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("{\"access_token\":\"tok-" + tokenCalls.get() + "\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"audit.read\"}")
                .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        okta.start();
        api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/data", ex -> {
            authHeaderSeenByApi.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        api.start();
    }

    @AfterEach
    void stop() {
        okta.stop(0);
        api.stop(0);
    }

    private ApplicationContextRunner runner() {
        ClientRegistration registration = ClientRegistration.withRegistrationId("audit-client")
            .clientId("svc-upgrade").clientSecret("local-test-secret")
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .tokenUri("http://127.0.0.1:" + okta.getAddress().getPort() + "/token")
            .scope("audit.read").build();
        return new ApplicationContextRunner()
            .withBean(ReactiveClientRegistrationRepository.class, () -> new InMemoryReactiveClientRegistrationRepository(registration))
            .withConfiguration(AutoConfigurations.of(ServiceClientAutoConfiguration.class));
    }

    @Test
    void callsCarryAClientCredentialsTokenThatIsCached() {
        runner().withPropertyValues("bank.security.client.registration-id=audit-client").run(ctx -> {
            var client = WebClient.builder().filter(ctx.getBean("serviceTokenFilter", ExchangeFilterFunction.class)).build();
            String url = "http://127.0.0.1:" + api.getAddress().getPort() + "/data";

            assertThat(client.get().uri(url).retrieve().bodyToMono(String.class).block()).isEqualTo("ok");
            assertThat(authHeaderSeenByApi.get()).isEqualTo("Bearer tok-1");
            assertThat(tokenRequestBody.get()).contains("grant_type=client_credentials").contains("scope=audit.read");

            client.get().uri(url).retrieve().bodyToMono(String.class).block();
            assertThat(tokenCalls.get()).as("second call reuses the cached token").isEqualTo(1);
        });
    }

    @Test
    void nothingIsCreatedUnlessConfigured() {
        runner().run(ctx -> assertThat(ctx).doesNotHaveBean("serviceTokenFilter"));
    }
}
