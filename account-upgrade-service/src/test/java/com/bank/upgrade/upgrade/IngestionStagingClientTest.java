package com.bank.upgrade.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.upgrade.common.api.StagedAccount;
import com.bank.upgrade.common.messaging.NonRetryableEventException;
import com.bank.upgrade.test.OracleTestConfig;
import com.bank.upgrade.upgrade.staging.StagingSource;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * The real client with the real token wiring, against a small HTTP server playing both the token endpoint and the
 * ingestion service. Checks that a client-credentials token is fetched and sent, and how errors are classified.
 */
@Import(OracleTestConfig.class)
@SpringBootTest(properties = {"bank.upgrade.schedule.enabled=false", "bank.upgrade.gateway=simulated"})
class IngestionStagingClientTest {

    static final HttpServer SERVER;
    static final List<String> tokenRequests = new CopyOnWriteArrayList<>();
    static final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        SERVER.createContext("/token", exchange -> {
            tokenRequests.add(URLDecoder.decode(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), StandardCharsets.UTF_8));
            respond(exchange, 200, "application/json",
                "{\"access_token\":\"service-token-1\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"staging.read\"}");
        });
        SERVER.createContext("/internal/files/", exchange -> {
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/internal/files/1/accounts")) {
                respond(exchange, 200, "application/x-ndjson", """
                    {"id":11,"rowNo":2,"accountNo":"S1","customerId":"C1","customerName":"Asha","email":"a@example.com","currentType":"SAVINGS","balance":10.50,"kycStatus":"VERIFIED"}
                    {"id":12,"rowNo":3,"accountNo":"S2","customerId":"C2","customerName":"Ben","email":"b@example.com","currentType":"CURRENT","balance":null,"kycStatus":null}
                    {"id":13,"rowNo":4,"accountNo":"S3","customerId":"C3","customerName":"Chen","email":"c@example.com","currentType":"SAVINGS","balance":0,"kycStatus":"VERIFIED"}
                    """);
            } else if (path.equals("/internal/files/2/accounts")) {
                respond(exchange, 404, "application/json", "");
            } else {
                respond(exchange, 503, "application/json", "");
            }
        });
        SERVER.start();
    }

    static void respond(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + SERVER.getAddress().getPort();
        r.add("bank.upgrade.ingestion-url", () -> base);
        r.add("spring.security.oauth2.client.provider.idp.token-uri", () -> base + "/token");
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @Autowired StagingSource staging;

    @BeforeEach
    void reset() {
        authorizationHeaders.clear();
    }

    @Test
    void streamsTheAccountsWithAClientCredentialsToken() {
        List<StagedAccount> accounts = staging.accounts(1).collectList().block();

        assertThat(accounts).extracting(StagedAccount::accountNo).containsExactly("S1", "S2", "S3");
        assertThat(accounts.get(0).balance()).isEqualByComparingTo("10.50");
        assertThat(accounts.get(1).balance()).isNull();
        assertThat(authorizationHeaders).containsExactly("Bearer service-token-1");
        assertThat(tokenRequests).anySatisfy(body -> assertThat(body)
            .contains("grant_type=client_credentials")
            .contains("scope=staging.read"));
    }

    @Test
    void theTokenIsReusedUntilItExpires() {
        staging.accounts(1).blockLast();
        int before = tokenRequests.size();

        staging.accounts(1).blockLast();

        assertThat(tokenRequests).hasSize(before);
    }

    @Test
    void anUnknownFileCannotSucceedOnRetry() {
        assertThatThrownBy(() -> staging.accounts(2).blockLast()).isInstanceOf(NonRetryableEventException.class);
    }

    @Test
    void aServerErrorIsAnOrdinaryErrorSoTheConsumerRetries() {
        assertThatThrownBy(() -> staging.accounts(3).blockLast())
            .isInstanceOf(WebClientResponseException.ServiceUnavailable.class)
            .isNotInstanceOf(NonRetryableEventException.class);
    }
}
