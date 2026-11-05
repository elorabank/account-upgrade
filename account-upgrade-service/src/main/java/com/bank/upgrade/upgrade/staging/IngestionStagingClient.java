package com.bank.upgrade.upgrade.staging;

import com.bank.upgrade.common.api.StagedAccount;
import com.bank.upgrade.common.messaging.NonRetryableEventException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Streams a file's accounts from {@code GET /internal/files/{fileId}/accounts} on the ingestion service, with this
 * service's own client-credentials token (scope {@code staging.read}). The body is newline-delimited JSON, decoded
 * one account at a time, so memory stays flat however large the file is.
 * <p>
 * 404 means the file does not exist: retrying cannot help, so the event goes to the dead-letter topic. Anything
 * else (409 still loading, 5xx, connection refused) is an ordinary error and the consumer retries with backoff.
 */
public class IngestionStagingClient implements StagingSource {

    private final WebClient webClient;

    public IngestionStagingClient(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Flux<StagedAccount> accounts(long fileId) {
        return webClient.get()
            .uri("/internal/files/{fileId}/accounts", fileId)
            .accept(MediaType.APPLICATION_NDJSON)
            .retrieve()
            .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(), r -> notFound(fileId, r))
            .bodyToFlux(StagedAccount.class);
    }

    private static Mono<Throwable> notFound(long fileId, ClientResponse response) {
        return response.releaseBody()
            .then(Mono.error(new NonRetryableEventException("The ingestion service has no file " + fileId)));
    }
}
