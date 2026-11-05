package com.bank.upgrade.communication.api;

import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.events.UserAction;
import com.bank.upgrade.common.messaging.OutboxService;
import com.bank.upgrade.common.persistence.BlockingJpa;
import com.bank.upgrade.common.security.ActorResolver;
import com.bank.upgrade.communication.domain.WelcomeLetter;
import com.bank.upgrade.communication.domain.WelcomeLetterRepository;
import com.bank.upgrade.common.api.PageView;
import jakarta.persistence.criteria.Predicate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Welcome emails for the ops console. {@code GET /api/notifications} lists them (newest first, by status or account),
 * {@code GET /api/notifications/summary} counts them by status, and {@code POST /api/notifications/{id}/resend} sends
 * one again, for example after the customer changed their email or says it never arrived (ops admins and support).
 * A resent letter goes back to PENDING with a fresh set of attempts, and the request is recorded for the audit trail.
 */
@RestController
public class NotificationController {

    public record ResendAccepted(long id, String status) {}

    public record LetterView(
            long id, long upgradeRequestId, String accountNo, String recipient, String subject, String status,
            int attempts, String errorMessage, Instant generatedAt, Instant sentAt, Instant nextAttemptAt) {

        static LetterView of(WelcomeLetter l) {
            return new LetterView(l.getId(), l.getUpgradeRequestId(), l.getAccountNo(), l.getRecipient(), l.getSubject(),
                l.getStatus(), l.getAttempts(), l.getErrorMessage(), l.getGeneratedAt(), l.getSentAt(), l.getNextAttemptAt());
        }
    }

    /** @param since letters generated from this moment (default: the last 24 hours) */
    public record Summary(Instant since, Map<String, Long> byStatus, long total) {}

    private final WelcomeLetterRepository letters;
    private final OutboxService outbox;
    private final BlockingJpa jpa;

    public NotificationController(WelcomeLetterRepository letters, OutboxService outbox, BlockingJpa jpa) {
        this.letters = letters;
        this.outbox = outbox;
        this.jpa = jpa;
    }

    @GetMapping("/api/notifications")
    public Mono<PageView<LetterView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String accountNo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var request = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200), Sort.by(Sort.Order.desc("id")));
        Specification<WelcomeLetter> filter = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (status != null && !status.isBlank()) where.add(cb.equal(root.get("status"), status));
            if (accountNo != null && !accountNo.isBlank()) where.add(cb.equal(root.get("accountNo"), accountNo.trim()));
            return cb.and(where.toArray(Predicate[]::new));
        };
        return jpa.call(() -> letters.findAll(filter, request)).map(result -> PageView.of(result, LetterView::of));
    }

    @GetMapping("/api/notifications/summary")
    public Mono<Summary> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        Instant from = since != null ? since : Instant.now().minus(Duration.ofDays(1));
        return jpa.call(() -> {
            Map<String, Long> counts = new LinkedHashMap<>();
            List.of(WelcomeLetter.PENDING, WelcomeLetter.SENT, WelcomeLetter.FAILED).forEach(s -> counts.put(s, 0L));
            for (Object[] row : letters.countByStatusSince(from)) {
                counts.put((String) row[0], (Long) row[1]);
            }
            return new Summary(from, counts, counts.values().stream().mapToLong(Long::longValue).sum());
        });
    }

    @PostMapping("/api/notifications/{id}/resend")
    public Mono<ResponseEntity<ResendAccepted>> resend(@PathVariable long id) {
        return ActorResolver.current()
            .flatMap(actor -> jpa.callInTransaction(() -> queueAgain(id, actor)))
            .map(found -> found
                .map(letter -> ResponseEntity.status(HttpStatus.ACCEPTED).body(new ResendAccepted(letter.getId(), letter.getStatus())))
                .orElseGet(() -> ResponseEntity.notFound().build()));
    }

    private Optional<WelcomeLetter> queueAgain(long id, String actor) {
        return letters.findById(id).map(letter -> {
            letter.setStatus(WelcomeLetter.PENDING);
            letter.setAttempts(0);
            letter.setNextAttemptAt(null);
            letter.setErrorMessage(null);
            outbox.publish(Topics.USER_ACTION, letter.getAccountNo(), "UserAction", letter.getAccountNo(),
                new UserAction("RESEND_WELCOME_EMAIL", "WELCOME_LETTER", String.valueOf(id), letter.getAccountNo()),
                actor);
            return letter;
        });
    }
}
