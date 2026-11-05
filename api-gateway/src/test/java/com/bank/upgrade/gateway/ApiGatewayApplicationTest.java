package com.bank.upgrade.gateway;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.blockhound.BlockHound;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiGatewayApplicationTest {

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
}
