package com.bank.upgrade.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOAuth2Login;

import com.bank.upgrade.test.TestJwt;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + TestJwt.ISSUER,
        "OKTA_DOMAIN=okta.test",
        "OKTA_AUTH_SERVER_ID=default",
        "OKTA_GATEWAY_CLIENT_ID=gateway-test-client"
    })
@AutoConfigureWebTestClient
class GatewaySecurityTest {

    static final TestJwt JWT = new TestJwt();
    static final HttpServer DOWNSTREAM = startDownstream();
    static final AtomicInteger DOWNSTREAM_CALLS = new AtomicInteger();
    static final AtomicReference<String> SEEN_AUTH = new AtomicReference<>();
    static final AtomicReference<String> SEEN_COOKIE = new AtomicReference<>();

    private static HttpServer startDownstream() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                DOWNSTREAM_CALLS.incrementAndGet();
                SEEN_AUTH.set(ex.getRequestHeaders().getFirst("Authorization"));
                SEEN_COOKIE.set(ex.getRequestHeaders().getFirst("Cookie"));
                byte[] body = "downstream".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            server.start();
            return server;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JWT::jwkSetUri);
        r.add("spring.cloud.gateway.server.webflux.routes[0].id", () -> "stub");
        r.add("spring.cloud.gateway.server.webflux.routes[0].uri", () -> "http://127.0.0.1:" + DOWNSTREAM.getAddress().getPort());
        r.add("spring.cloud.gateway.server.webflux.routes[0].predicates[0]", () -> "Path=/api/**");
    }

    @AfterAll
    static void stop() {
        JWT.stop();
        DOWNSTREAM.stop(0);
    }

    @Autowired WebTestClient webClient;
    @Autowired ReactiveClientRegistrationRepository registrations;

    @BeforeEach
    void reset() {
        DOWNSTREAM_CALLS.set(0);
        SEEN_AUTH.set(null);
        SEEN_COOKIE.set(null);
    }

    WebTestClient web() {
        return webClient;  // Boot already applies springSecurity() to the injected client
    }

    @Test
    void bearerTokenIsValidatedAndPassedDownstreamUnchanged() {
        String token = JWT.user("AU_OPS_ADMIN");
        web().get().uri("/api/files").headers(h -> h.setBearerAuth(token)).exchange()
            .expectStatus().isOk().expectBody(String.class).isEqualTo("downstream");
        assertThat(SEEN_AUTH.get()).isEqualTo("Bearer " + token);
    }

    @Test
    void badBearerTokenNeverReachesDownstream() {
        for (String bad : new String[] {
                "garbage",
                JWT.custom(TestJwt.ISSUER, TestJwt.AUDIENCE, Instant.now().minusSeconds(600)),
                JWT.signedByStranger()}) {
            web().get().uri("/api/files").headers(h -> h.setBearerAuth(bad)).exchange().expectStatus().isUnauthorized();
        }
        assertThat(DOWNSTREAM_CALLS.get()).isZero();
    }

    @Test
    void apiCallWithoutCredentialsGets401NotARedirect() {
        web().get().uri("/api/files").exchange().expectStatus().isUnauthorized();
        assertThat(DOWNSTREAM_CALLS.get()).isZero();
    }

    @Test
    void pageNavigationWithoutLoginIsSentToOkta() {
        web().get().uri("/").exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().valueMatches("Location", ".*/oauth2/authorization/okta");
    }

    @Test
    void loginRedirectGoesToOktaWithPkce() {
        web().get().uri("/oauth2/authorization/okta").exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().value("Location", loc -> {
                assertThat(loc).startsWith("https://okta.test/oauth2/default/v1/authorize?");
                assertThat(loc).contains("response_type=code", "client_id=gateway-test-client",
                    "code_challenge=", "code_challenge_method=S256");
                assertThat(loc).contains("scope=openid%20profile%20email%20offline_access");
                assertThat(loc).doesNotContain("upgrade.admin");
            });
    }

    @Test
    void signedInUserGetsTheirAccessTokenRelayedAndTheSessionCookieStays() {
        ClientRegistration okta = registrations.findByRegistrationId("okta").block();

        // mockOAuth2Login() signs the user in with an authorized client whose access token is "access-token"
        web().mutateWith(mockOAuth2Login().clientRegistration(okta))
            .get().uri("/api/files").header("Cookie", "SESSION=abc").exchange()
            .expectStatus().isOk();

        assertThat(SEEN_AUTH.get()).isEqualTo("Bearer access-token");
        assertThat(SEEN_COOKIE.get()).as("session cookie must not leave the gateway").isNull();
    }

    @Test
    void healthStaysOpen() {
        web().get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    @Test
    void postFromABrowserSessionNeedsACsrfToken() {
        ClientRegistration okta = registrations.findByRegistrationId("okta").block();
        web().mutateWith(mockOAuth2Login().clientRegistration(okta))
            .post().uri("/api/jobs/upgrade/run").exchange().expectStatus().isForbidden();
        assertThat(DOWNSTREAM_CALLS.get()).isZero();
    }
}
