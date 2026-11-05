package com.bank.upgrade.upgrade;

import com.bank.upgrade.test.OracleTestConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.blockhound.BlockHound;

@Import(OracleTestConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountUpgradeApplicationTest {

    @Autowired
    WebTestClient client;

    @Autowired
    org.springframework.context.ApplicationContext context;

    @BeforeAll
    static void installBlockHound() {
        // Fails any test that blocks an event-loop thread.
        BlockHound.install();
    }

    @Test
    void healthEndpointIsUp() {
        client.get().uri("/actuator/health").exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void apiRequiresAToken() {
        client.get().uri("/api/files").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/files").headers(h -> h.setBearerAuth("not-a-real-token")).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void callingOtherServicesDoesNotAddALoginChain() {
        // The OAuth2 client library is on the classpath for service tokens; it must not turn on browser login
        org.assertj.core.api.Assertions.assertThat(
            context.getBeansOfType(org.springframework.security.web.server.SecurityWebFilterChain.class)).hasSize(1);
        client.get().uri("/internal/files/1/accounts").exchange().expectStatus().isUnauthorized();
    }
}
