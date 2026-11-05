package com.bank.upgrade.upgrade.api;

import com.bank.upgrade.common.api.PageView;
import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.events.UserAction;
import com.bank.upgrade.common.messaging.OutboxService;
import com.bank.upgrade.common.persistence.BlockingJpa;
import com.bank.upgrade.common.security.ActorResolver;
import com.bank.upgrade.upgrade.domain.UpgradeRequest;
import com.bank.upgrade.upgrade.domain.UpgradeRequestRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Upgrade requests for the ops console: search, the day's totals, and retry of a FAILED upgrade (ops admin).
 * The customer's email is not shown here; the name is, so ops can recognise an account.
 */
@RestController
public class UpgradesController {

    public record UpgradeView(
            long id, String accountNo, String customerName, String currentType, String targetType, String status,
            int attemptCount, String reason, LocalDate businessDate, Long sourceFileId, Instant updatedAt) {

        static UpgradeView of(UpgradeRequest r) {
            return new UpgradeView(r.getId(), r.getAccountNo(), r.getCustomerName(), r.getCurrentType(), r.getTargetType(),
                r.getStatus(), r.getAttemptCount(), r.getReason(), r.getBusinessDate(), r.getSourceFileId(), r.getUpdatedAt());
        }
    }

    /** @param businessDate the day asked for, or the latest day there is; null when there are no requests at all */
    public record Summary(LocalDate businessDate, Map<String, Long> byStatus, long total) {}

    public record RetryResult(long id, String status, String message) {}

    private static final List<String> STATUSES = List.of(
        UpgradeRequest.PENDING, UpgradeRequest.IN_PROGRESS, UpgradeRequest.UPGRADED, UpgradeRequest.REJECTED, UpgradeRequest.FAILED);

    private final UpgradeRequestRepository requests;
    private final OutboxService outbox;
    private final BlockingJpa jpa;

    public UpgradesController(UpgradeRequestRepository requests, OutboxService outbox, BlockingJpa jpa) {
        this.requests = requests;
        this.outbox = outbox;
        this.jpa = jpa;
    }

    @GetMapping("/api/upgrades")
    public Mono<PageView<UpgradeView>> search(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
            @RequestParam(required = false) String accountNo,
            @RequestParam(required = false) Long fileId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var request = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200), Sort.by(Sort.Order.desc("id")));
        Specification<UpgradeRequest> filter = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (status != null && !status.isBlank()) where.add(cb.equal(root.get("status"), status));
            if (businessDate != null) where.add(cb.equal(root.get("businessDate"), businessDate));
            if (accountNo != null && !accountNo.isBlank()) where.add(cb.equal(root.get("accountNo"), accountNo.trim()));
            if (fileId != null) where.add(cb.equal(root.get("sourceFileId"), fileId));
            return cb.and(where.toArray(Predicate[]::new));
        };
        return jpa.call(() -> requests.findAll(filter, request)).map(result -> PageView.of(result, UpgradeView::of));
    }

    @GetMapping("/api/upgrades/summary")
    public Mono<Summary> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        return jpa.call(() -> {
            LocalDate day = businessDate != null ? businessDate : requests.findLatestBusinessDate();
            Map<String, Long> counts = new LinkedHashMap<>();
            STATUSES.forEach(s -> counts.put(s, 0L));
            if (day != null) {
                for (Object[] row : requests.countByStatus(day)) {
                    counts.put((String) row[0], (Long) row[1]);
                }
            }
            return new Summary(day, counts, counts.values().stream().mapToLong(Long::longValue).sum());
        });
    }

    /** Only a FAILED upgrade can be retried: it goes back to PENDING with fresh attempts for the next run. */
    @PostMapping("/api/upgrades/{id}/retry")
    public Mono<ResponseEntity<RetryResult>> retry(@PathVariable long id) {
        return ActorResolver.current().flatMap(actor -> jpa.callInTransaction(() -> requests.findById(id)
            .map(r -> {
                if (!UpgradeRequest.FAILED.equals(r.getStatus())) {
                    return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new RetryResult(id, r.getStatus(), "Only a FAILED upgrade can be retried"));
                }
                r.setStatus(UpgradeRequest.PENDING);
                r.setAttemptCount(0);
                r.setReason(null);
                r.setRunId(null);
                outbox.publish(Topics.USER_ACTION, r.getAccountNo(), "UserAction", r.getAccountNo(),
                    new UserAction("RETRY_UPGRADE", "UPGRADE_REQUEST", String.valueOf(id), r.getAccountNo()), actor);
                return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new RetryResult(id, r.getStatus(), "Queued for the next upgrade run"));
            })
            .orElseGet(() -> ResponseEntity.notFound().build())));
    }
}
