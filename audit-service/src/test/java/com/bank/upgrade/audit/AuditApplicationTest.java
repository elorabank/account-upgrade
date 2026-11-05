package com.bank.upgrade.audit;

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
class AuditApplicationTest {

    @Autowired
    WebTestClient client;

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
}
