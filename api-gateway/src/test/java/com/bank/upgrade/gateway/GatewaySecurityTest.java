package com.bank.upgrade.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOAuth2Login;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;

import com.bank.upgrade.test.TestJwt;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.springframework.http.MediaType;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@Import(RedisTestConfig.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + TestJwt.ISSUER,
        "bank.idp.issuer-uri=https://idp.test/realms/account-upgrade",
        "IDP_CLIENT_ID=gateway-test-client"
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
    void pageNavigationWithoutLoginIsSentToTheLogin() {
        web().get().uri("/").exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().valueMatches("Location", ".*/oauth2/authorization/login");
    }

    @Test
    void loginRedirectGoesToTheIdentityProviderWithPkce() {
        web().get().uri("/oauth2/authorization/login").exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().value("Location", loc -> {
                assertThat(loc).startsWith("https://idp.test/realms/account-upgrade/protocol/openid-connect/auth?");
                assertThat(loc).contains("response_type=code", "client_id=gateway-test-client",
                    "code_challenge=", "code_challenge_method=S256");
                assertThat(loc).contains("scope=openid%20profile%20email");
                assertThat(loc).doesNotContain("upgrade.admin");
            });
    }

    @Test
    void signedInUserGetsTheirAccessTokenRelayedAndTheSessionCookieStays() {
        ClientRegistration login = registrations.findByRegistrationId("login").block();

        // mockOAuth2Login() signs the user in with an authorized client whose access token is "access-token"
        web().mutateWith(mockOAuth2Login().clientRegistration(login))
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
        ClientRegistration login = registrations.findByRegistrationId("login").block();
        web().mutateWith(mockOAuth2Login().clientRegistration(login))
            .post().uri("/api/jobs/upgrade/run").exchange().expectStatus().isForbidden();
        assertThat(DOWNSTREAM_CALLS.get()).isZero();
    }

    @Test
    void meShowsTheSignedInPersonsOwnGroupsOnly() {
        ClientRegistration login = registrations.findByRegistrationId("login").block();
        web().mutateWith(mockOidcLogin().clientRegistration(login).idToken(t -> t
                .subject("u1").claim("email", "sam@bank.example").claim("name", "Sam Support")
                .claim("groups", List.of("AU_SUPPORT", "Everyone"))))
            .get().uri("/me").exchange()
            .expectStatus().isOk().expectBody()
            .jsonPath("$.name").isEqualTo("Sam Support")
            .jsonPath("$.email").isEqualTo("sam@bank.example")
            .jsonPath("$.roles.length()").isEqualTo(1)
            .jsonPath("$.roles[0]").isEqualTo("AU_SUPPORT");
    }

    @Test
    void meWithoutLoginIs401SoTheAppCanSendThePersonToTheLogin() {
        web().get().uri("/me").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void theConsoleIsServedForItsOwnAddresses() {
        ClientRegistration login = registrations.findByRegistrationId("login").block();
        var signedIn = web().mutateWith(mockOidcLogin().clientRegistration(login));

        for (String page : new String[] {"/", "/upgrades", "/files/12"}) {
            signedIn.get().uri(page).accept(MediaType.TEXT_HTML).exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(html -> assertThat(html).contains("<div id=\"root\">"));
        }
        assertThat(DOWNSTREAM_CALLS.get()).isZero();
    }

    @Test
    void aPostWithTheCsrfCookieValueInTheHeaderGoesThrough() {
        ClientRegistration login = registrations.findByRegistrationId("login").block();
        var signedIn = web().mutateWith(mockOAuth2Login().clientRegistration(login));

        // Any browser request hands out the token as a readable cookie, as the app expects
        var cookie = signedIn.get().uri("/me").exchange().returnResult(String.class)
            .getResponseCookies().getFirst("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isFalse();

        signedIn.post().uri("/api/jobs/upgrade/run")
            .cookie("XSRF-TOKEN", cookie.getValue())
            .header("X-XSRF-TOKEN", cookie.getValue())
            .exchange().expectStatus().isOk();
        assertThat(DOWNSTREAM_CALLS.get()).isEqualTo(1);
    }

    @Test
    void signingOutAlsoEndsTheSessionAtTheIdentityProvider() {
        ClientRegistration login = registrations.findByRegistrationId("login").block();

        web().mutateWith(mockOidcLogin().clientRegistration(login)).mutateWith(csrf())
            .post().uri("/logout").exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().value("Location", loc -> {
                assertThat(loc).startsWith("https://idp.test/realms/account-upgrade/protocol/openid-connect/logout?");
                assertThat(loc).contains("id_token_hint=", "post_logout_redirect_uri=");
            });
    }
}
