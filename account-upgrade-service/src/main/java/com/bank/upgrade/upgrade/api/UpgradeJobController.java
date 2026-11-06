package com.bank.upgrade.upgrade.api;

import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.events.UpgradeRunRequested;
import com.bank.upgrade.common.events.UserAction;
import com.bank.upgrade.common.messaging.OutboxService;
import com.bank.upgrade.common.persistence.BlockingJpa;
import com.bank.upgrade.common.security.ActorResolver;
import com.bank.upgrade.upgrade.domain.UpgradeRequest;
import com.bank.upgrade.upgrade.domain.UpgradeRequestRepository;
import com.bank.upgrade.upgrade.run.UpgradeRunner;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * {@code POST /api/jobs/upgrade/run} (ops admin): run the 03:00 upgrade now, for example after retrying failed
 * upgrades. This copy starts at once and announces the run on Kafka, so every other copy joins it and they share the
 * work. 202 when it started, 409 when a run is already going (some copy holds a live lease).
 */
@RestController
public class UpgradeJobController {

    public record JobStart(boolean started, String runId, String message) {}

    private final UpgradeRunner runner;
    private final UpgradeRequestRepository requests;
    private final OutboxService outbox;
    private final BlockingJpa jpa;
    private final Clock clock;

    public UpgradeJobController(UpgradeRunner runner, UpgradeRequestRepository requests, OutboxService outbox,
            BlockingJpa jpa, Clock clock) {
        this.runner = runner;
        this.requests = requests;
        this.outbox = outbox;
        this.jpa = jpa;
        this.clock = clock;
    }

    @PostMapping("/api/jobs/upgrade/run")
    public Mono<ResponseEntity<JobStart>> run() {
        String runId = UUID.randomUUID().toString();
        return ActorResolver.current().flatMap(actor -> jpa.callInTransaction(() -> {
            if (runner.busy() || requests.existsByStatusAndClaimedUntilAfter(UpgradeRequest.IN_PROGRESS, clock.instant())) {
                return false;
            }
            outbox.publish(Topics.USER_ACTION, "upgrade", "UserAction", "upgrade",
                new UserAction("RUN_UPGRADE_JOB", "JOB", runId, null), actor);
            outbox.publish(Topics.UPGRADE_RUN_REQUESTED, runId, "UpgradeRunRequested", runId, new UpgradeRunRequested(runId), actor);
            return true;
        })).map(started -> {
            if (!started) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(new JobStart(false, null, "An upgrade run is already going"));
            }
            // This copy starts straight away; the others join when the announcement reaches them
            Thread.ofVirtual().name("upgrade-run").start(() -> runner.runIfIdle(runId));
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(new JobStart(true, runId, "Upgrade run started on every copy"));
        });
    }
}
