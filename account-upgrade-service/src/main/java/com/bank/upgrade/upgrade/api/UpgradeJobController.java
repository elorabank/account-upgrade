package com.bank.upgrade.upgrade.api;

import com.bank.upgrade.common.correlation.CorrelationId;
import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.events.UserAction;
import com.bank.upgrade.common.messaging.OutboxService;
import com.bank.upgrade.common.persistence.BlockingJpa;
import com.bank.upgrade.common.scheduling.ManualRunner;
import com.bank.upgrade.common.security.ActorResolver;
import com.bank.upgrade.upgrade.run.UpgradeRunner;
import com.bank.upgrade.upgrade.run.UpgradeScheduler;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * {@code POST /api/jobs/upgrade/run} (ops admin): run the 03:00 upgrade now, for example after retrying failed
 * upgrades. 202 when it started (in the background), 409 when a run is already going anywhere.
 */
@RestController
public class UpgradeJobController {

    public record JobStart(boolean started, String correlationId, String message) {}

    private final ManualRunner runner;
    private final UpgradeRunner upgrades;
    private final OutboxService outbox;
    private final BlockingJpa jpa;

    public UpgradeJobController(ManualRunner runner, UpgradeRunner upgrades, OutboxService outbox, BlockingJpa jpa) {
        this.runner = runner;
        this.upgrades = upgrades;
        this.outbox = outbox;
        this.jpa = jpa;
    }

    @PostMapping("/api/jobs/upgrade/run")
    public Mono<ResponseEntity<JobStart>> run() {
        String correlationId = CorrelationId.newId();
        return ActorResolver.current().flatMap(actor -> jpa.call(() ->
                runner.tryStart(UpgradeScheduler.LOCK_NAME, UpgradeScheduler.LOCK_AT_MOST_FOR, correlationId, upgrades::runOnce))
            .flatMap(started -> started
                ? jpa.runInTransaction(() -> outbox.publish(Topics.USER_ACTION, "upgrade", "UserAction", "upgrade",
                        new UserAction("RUN_UPGRADE_JOB", "JOB", "upgrade", null), actor))
                    .thenReturn(ResponseEntity.status(HttpStatus.ACCEPTED).body(new JobStart(true, correlationId, "Upgrade run started")))
                : Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body(new JobStart(false, null, "An upgrade run is already going")))));
    }
}
