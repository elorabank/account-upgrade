package com.bank.upgrade.gateway;

import com.bank.upgrade.test.RedisTestConfig;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.session.ReactiveSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Login sessions live in Redis, so every gateway copy sees every session: a login started on one can finish on another. */
@Import(RedisTestConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "bank.idp.issuer-uri=https://idp.test/realms/account-upgrade",
    "IDP_CLIENT_ID=gateway-test-client"
})
class GatewaySessionTest {

    @Autowired WebTestClient client;
    @Autowired ReactiveStringRedisTemplate redis;

    /** Starts a login: the gateway remembers the login request (state, PKCE verifier) in the session and sends the browser off. */
    private String startLogin() {
        var result = client.get().uri("/oauth2/authorization/login").exchange()
            .expectStatus().is3xxRedirection()
            .returnResult(Void.class);
        var cookie = result.getResponseCookies().getFirst("SESSION");
        assertThat(cookie).as("session cookie").isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        return cookie.getValue();
    }

    @Test
    void theSessionIsKeptInRedisNotInTheGateway() {
        String sessionId = startLogin();

        var keys = redis.keys("account-upgrade:gateway:sessions:*").collectList().block();

        assertThat(keys).anyMatch(key -> key.endsWith(sessionId));
        Long ttl = redis.getExpire("account-upgrade:gateway:sessions:" + sessionId).map(d -> d.toSeconds()).block();
        assertThat(ttl).as("expires after 30 minutes without activity").isBetween(1700L, 1800L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSecondGatewayCopySeesALoginStartedOnTheFirst() {
        String sessionId = startLogin();

        // Command-line arguments, so they win over application.yml like real environment settings would
        try (ConfigurableApplicationContext secondCopy = new SpringApplicationBuilder(ApiGatewayApplication.class).run(
                "--server.port=0",
                "--bank.idp.issuer-uri=https://idp.test/realms/account-upgrade",
                "--IDP_CLIENT_ID=gateway-test-client",
                "--spring.data.redis.host=" + RedisTestConfig.REDIS.getHost(),
                "--spring.data.redis.port=" + RedisTestConfig.REDIS.getMappedPort(6379))) {
            var sessions = (ReactiveSessionRepository<Session>) secondCopy.getBean(ReactiveSessionRepository.class);

            Session seen = sessions.findById(sessionId).block();

            assertThat(seen).as("the second copy finds the session by the browser's cookie").isNotNull();
            assertThat(seen.getAttributeNames()).anyMatch(name -> name.contains("AUTHORIZATION_REQUEST"));
        }
    }
}
