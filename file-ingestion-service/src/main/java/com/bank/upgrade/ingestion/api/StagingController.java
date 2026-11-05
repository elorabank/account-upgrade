package com.bank.upgrade.ingestion.api;

import com.bank.upgrade.common.api.StagedAccount;
import com.bank.upgrade.ingestion.domain.FileIngestionLogRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * For the upgrade service only (scope {@code staging.read}, not routed by the gateway): the accounts of a loaded
 * file as newline-delimited JSON, streamed in id order. 404 for an unknown file, 409 while it is not COMPLETED.
 */
@RestController
public class StagingController {

    private final FileIngestionLogRepository logs;
    private final StagingStream stream;

    public StagingController(FileIngestionLogRepository logs, StagingStream stream) {
        this.logs = logs;
        this.stream = stream;
    }

    @GetMapping(value = "/internal/files/{fileId}/accounts", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public Mono<ResponseEntity<Flux<StagedAccount>>> accounts(@PathVariable long fileId) {
        return Mono.fromCallable(() -> logs.findById(fileId))
            .subscribeOn(Schedulers.boundedElastic())
            .map(found -> found
                .map(file -> "COMPLETED".equals(file.getStatus())
                    ? ResponseEntity.ok().contentType(MediaType.APPLICATION_NDJSON).body(stream.accounts(fileId))
                    : ResponseEntity.status(HttpStatus.CONFLICT).<Flux<StagedAccount>>build())
                .orElseGet(() -> ResponseEntity.notFound().build()));
    }
}
