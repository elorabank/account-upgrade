package com.bank.upgrade.upgrade.staging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.upgrade.common.api.StagedAccount;
import com.bank.upgrade.common.messaging.NonRetryableEventException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * The client that reads a loaded file's accounts, against a small in-process HTTP server playing the ingestion
 * service. (Fetching the client-credentials token is ServiceClientAutoConfigurationTest's job.)
 */
class IngestionStagingClientTest {

    static HttpServer server;
    static StagingSource staging;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/files/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/internal/files/1/accounts")) {
                respond(exchange, 200, """
                    {"id":11,"rowNo":2,"accountNo":"S1","customerId":"C1","customerName":"Asha","email":"a@example.com","currentType":"SAVINGS","balance":10.50,"kycStatus":"VERIFIED"}
                    {"id":12,"rowNo":3,"accountNo":"S2","customerId":"C2","customerName":"Ben","email":"b@example.com","currentType":"CURRENT","balance":null,"kycStatus":null}
                    {"id":13,"rowNo":4,"accountNo":"S3","customerId":"C3","customerName":"Chen","email":"c@example.com","currentType":"SAVINGS","balance":0,"kycStatus":"VERIFIED"}
                    """);
            } else if (path.equals("/internal/files/2/accounts")) {
                respond(exchange, 404, "");
            } else {
                respond(exchange, 503, "");
            }
        });
        server.start();
        staging = new IngestionStagingClient(WebClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build());
    }

    static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", status == 200 ? "application/x-ndjson" : "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @Test
    void streamsTheAccountsOfAFile() {
        List<StagedAccount> accounts = staging.accounts(1).collectList().block();

        assertThat(accounts).extracting(StagedAccount::accountNo).containsExactly("S1", "S2", "S3");
        assertThat(accounts.get(0).balance()).isEqualByComparingTo("10.50");
        assertThat(accounts.get(1).balance()).isNull();
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
