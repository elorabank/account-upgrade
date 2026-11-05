package com.bank.upgrade.audit.api;

import com.bank.upgrade.audit.domain.AuditEvent;
import com.bank.upgrade.audit.domain.AuditEventRepository;
import com.bank.upgrade.common.persistence.BlockingJpa;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * {@code GET /api/audit/events}: search the audit trail, newest first. Every filter is optional and they combine:
 * {@code accountNo} gives one account's history, {@code correlationId} one business action across all services
 * (for example one file, from load to emails). Ops admins, auditors and the {@code audit.read} scope may call it.
 */
@RestController
public class AuditQueryController {

    static final int MAX_PAGE_SIZE = 200;

    public record AuditEventView(
            String eventId,
            String topic,
            String eventType,
            String sourceService,
            String accountNo,
            String actor,
            String correlationId,
            String summary,
            Instant occurredAt,
            Instant recordedAt) {

        static AuditEventView of(AuditEvent e) {
            return new AuditEventView(e.getEventId(), e.getTopic(), e.getEventType(), e.getSourceService(),
                e.getAccountNo(), e.getActor(), e.getCorrelationId(), e.getSummary(), e.getOccurredAt(), e.getRecordedAt());
        }
    }

    public record AuditPage(List<AuditEventView> items, int page, int size, long total) {}

    private final AuditEventRepository events;
    private final BlockingJpa jpa;

    public AuditQueryController(AuditEventRepository events, BlockingJpa jpa) {
        this.events = events;
        this.jpa = jpa;
    }

    @GetMapping("/api/audit/events")
    public Mono<AuditPage> search(
            @RequestParam(required = false) String accountNo,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        var request = PageRequest.of(safePage, safeSize, Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")));
        var filter = filter(accountNo, correlationId, eventType, actor, from, to);
        return jpa.call(() -> events.findAll(filter, request))
            .map(result -> new AuditPage(result.map(AuditEventView::of).getContent(), safePage, safeSize, result.getTotalElements()));
    }

    private static Specification<AuditEvent> filter(
            String accountNo, String correlationId, String eventType, String actor, Instant from, Instant to) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (accountNo != null && !accountNo.isBlank()) where.add(cb.equal(root.get("accountNo"), accountNo));
            if (correlationId != null && !correlationId.isBlank()) where.add(cb.equal(root.get("correlationId"), correlationId));
            if (eventType != null && !eventType.isBlank()) where.add(cb.equal(root.get("eventType"), eventType));
            if (actor != null && !actor.isBlank()) where.add(cb.equal(root.get("actor"), actor));
            if (from != null) where.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            if (to != null) where.add(cb.lessThan(root.get("occurredAt"), to));
            return cb.and(where.toArray(Predicate[]::new));
        };
    }
}
