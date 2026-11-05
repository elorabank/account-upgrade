package com.bank.upgrade.ingestion.api;

import com.bank.upgrade.common.correlation.CorrelationId;
import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.events.UserAction;
import com.bank.upgrade.common.messaging.OutboxService;
import com.bank.upgrade.common.persistence.BlockingJpa;
import com.bank.upgrade.common.scheduling.ManualRunner;
import com.bank.upgrade.common.security.ActorResolver;
import com.bank.upgrade.ingestion.IngestionScheduler;
import com.bank.upgrade.ingestion.IngestionService;
import java.io.UncheckedIOException;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * {@code POST /api/jobs/ingestion/run} (ops admin): run the 01:00 load now, for example after a corrected file was
 * uploaded. 202 when it started (it runs in the background), 409 when a load is already running anywhere.
 */
@RestController
public class IngestionJobController {

    public record JobStart(boolean started, String correlationId, String message) {}

    private final ManualRunner runner;
    private final IngestionService service;
    private final OutboxService outbox;
    private final BlockingJpa jpa;

    public IngestionJobController(ManualRunner runner, IngestionService service, OutboxService outbox, BlockingJpa jpa) {
        this.runner = runner;
        this.service = service;
        this.outbox = outbox;
        this.jpa = jpa;
    }

    @PostMapping("/api/jobs/ingestion/run")
    public Mono<ResponseEntity<JobStart>> run() {
        String correlationId = CorrelationId.newId();
        return ActorResolver.current().flatMap(actor -> jpa.call(() ->
                runner.tryStart(IngestionScheduler.LOCK_NAME, IngestionScheduler.LOCK_AT_MOST_FOR, correlationId, this::load))
            .flatMap(started -> started
                ? jpa.runInTransaction(() -> outbox.publish(Topics.USER_ACTION, "ingestion", "UserAction", "ingestion",
                        new UserAction("RUN_INGESTION_JOB", "JOB", "ingestion", null), actor))
                    .thenReturn(ResponseEntity.status(HttpStatus.ACCEPTED).body(new JobStart(true, correlationId, "Load started")))
                : Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body(new JobStart(false, null, "A load is already running")))));
    }

    private void load() {
        try {
            service.runOnce();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
