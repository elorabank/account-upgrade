package com.bank.upgrade.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.upgrade.test.TestJwt;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Role matrix: every endpoint against every kind of caller, through the real security chain with
 * real signed tokens. Allowed calls reach a stub that answers 200; refused calls must be 403.
 */
@SpringBootTest(
    classes = AccessMatrixTest.App.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + TestJwt.ISSUER,
        "spring.security.oauth2.resourceserver.jwt.audiences=" + TestJwt.AUDIENCE
    })
@AutoConfigureWebTestClient
class AccessMatrixTest {

    static final TestJwt JWT = new TestJwt();

    @DynamicPropertySource
    static void jwks(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JWT::jwkSetUri);
    }

    @AfterAll
    static void stop() {
        JWT.stop();
    }

    @Configuration
    @EnableAutoConfiguration
    @Import(Stub.class)
    static class App {}

    /** Stands in for every real controller: if security lets a call through it answers 200. */
    @RestController
    static class Stub {
        @RequestMapping("/**")
        Mono<String> any() {
            return Mono.just("ok");
        }
    }

    @Autowired WebTestClient client;
    @Autowired ApplicationContext context;

    // who -> token
    static final Map<String, String> CALLERS = Map.ofEntries(
        Map.entry("OPS_ADMIN", JWT.user("AU_OPS_ADMIN")),
        Map.entry("OPS_VIEWER", JWT.user("AU_OPS_VIEWER")),
        Map.entry("SUPPORT", JWT.user("AU_SUPPORT")),
        Map.entry("AUDITOR", JWT.user("AU_AUDITOR")),
        Map.entry("no group", JWT.user()),
        Map.entry("unrelated group", JWT.user("Everyone")),
        Map.entry("svc upgrade.admin", JWT.service("svc-a", "upgrade.admin")),
        Map.entry("svc upgrade.read", JWT.service("svc-b", "upgrade.read")),
        Map.entry("svc audit.read", JWT.service("svc-c", "audit.read")),
        Map.entry("svc notification.resend", JWT.service("svc-d", "notification.resend")),
        Map.entry("svc staging.read", JWT.service("svc-e", "staging.read")));

    record Endpoint(HttpMethod method, String path, List<String> allowed) {}

    static final List<String> ANY_ROLE_OR_READ = List.of("OPS_ADMIN", "OPS_VIEWER", "SUPPORT", "AUDITOR", "svc upgrade.read");

    static final List<Endpoint> ENDPOINTS = List.of(
        new Endpoint(HttpMethod.POST, "/api/jobs/ingestion/run", List.of("OPS_ADMIN", "svc upgrade.admin")),
        new Endpoint(HttpMethod.POST, "/api/jobs/upgrade/run", List.of("OPS_ADMIN", "svc upgrade.admin")),
        new Endpoint(HttpMethod.POST, "/api/upgrades/42/retry", List.of("OPS_ADMIN", "svc upgrade.admin")),
        new Endpoint(HttpMethod.GET, "/api/files", ANY_ROLE_OR_READ),
        new Endpoint(HttpMethod.GET, "/api/upgrades", ANY_ROLE_OR_READ),
        new Endpoint(HttpMethod.POST, "/api/notifications/7/resend", List.of("OPS_ADMIN", "SUPPORT", "svc notification.resend")),
        new Endpoint(HttpMethod.GET, "/api/audit/events", List.of("OPS_ADMIN", "AUDITOR", "svc audit.read")),
        new Endpoint(HttpMethod.GET, "/internal/files/12/accounts", List.of("svc staging.read")),
        // nothing grants these: wrong method, or no rule yet
        new Endpoint(HttpMethod.DELETE, "/api/files/1", List.of()),
        new Endpoint(HttpMethod.POST, "/api/audit/events", List.of()),
        new Endpoint(HttpMethod.GET, "/api/notifications", List.of()),
        new Endpoint(HttpMethod.GET, "/api/something-new", List.of()),
        // internal paths: a person's role never opens them, and only the one rule exists
        new Endpoint(HttpMethod.DELETE, "/internal/files/12/accounts", List.of()),
        new Endpoint(HttpMethod.GET, "/internal/files/12", List.of()));

    static Stream<Arguments> matrix() {
        return ENDPOINTS.stream().flatMap(e -> CALLERS.keySet().stream().sorted().map(who ->
            Arguments.of(e.method() + " " + e.path() + " as " + who, e, who, e.allowed().contains(who))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("matrix")
    void roleMatrix(String label, Endpoint endpoint, String who, boolean allowed) {
        client.method(endpoint.method()).uri(endpoint.path())
            .headers(h -> h.setBearerAuth(CALLERS.get(who)))
            .exchange()
            .expectStatus().isEqualTo(allowed ? 200 : 403);
    }

    @ParameterizedTest(name = "{0} without a token")
    @MethodSource("endpointsOnly")
    void noTokenIsUnauthorized(String label, Endpoint endpoint) {
        client.method(endpoint.method()).uri(endpoint.path()).exchange().expectStatus().isUnauthorized();
    }

    static Stream<Arguments> endpointsOnly() {
        return ENDPOINTS.stream().map(e -> Arguments.of(e.method() + " " + e.path(), e));
    }

    @Test
    void badTokensAreUnauthorized() {
        var bad = Map.of(
            "garbage", "not-a-jwt",
            "wrong audience", JWT.custom(TestJwt.ISSUER, "api://other", Instant.now().plusSeconds(300)),
            "wrong issuer", JWT.custom("https://evil.test/oauth2/default", TestJwt.AUDIENCE, Instant.now().plusSeconds(300)),
            "expired", JWT.custom(TestJwt.ISSUER, TestJwt.AUDIENCE, Instant.now().minusSeconds(600)),
            "unknown signing key", JWT.signedByStranger());
        bad.forEach((why, token) -> client.get().uri("/api/files")
            .headers(h -> h.setBearerAuth(token)).exchange()
            .expectStatus().isUnauthorized());
    }

    @Test
    void healthIsOpenButOtherActuatorPathsNeedAToken() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
        client.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
        client.get().uri("/actuator/prometheus").exchange().expectStatus().isUnauthorized();
        client.get().uri("/actuator/prometheus").headers(h -> h.setBearerAuth(JWT.service("prometheus"))).exchange().expectStatus().isOk();
    }

    @Test
    void onlyOneSecurityChainExists() {
        assertThat(context.getBeansOfType(SecurityWebFilterChain.class)).hasSize(1);
    }

    @Test
    void errorsCarryNoSessionCookie() {
        var result = client.get().uri("/api/files").headers(h -> h.setBearerAuth(JWT.user("AU_OPS_ADMIN"))).exchange()
            .expectStatus().isOk().returnResult(String.class);
        assertThat(result.getResponseHeaders().get("Set-Cookie")).isNull();
    }
}
