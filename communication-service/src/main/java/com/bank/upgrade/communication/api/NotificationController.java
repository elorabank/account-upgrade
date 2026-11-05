package com.bank.upgrade.communication.api;

import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.events.UserAction;
import com.bank.upgrade.common.messaging.OutboxService;
import com.bank.upgrade.common.persistence.BlockingJpa;
import com.bank.upgrade.common.security.ActorResolver;
import com.bank.upgrade.communication.domain.WelcomeLetter;
import com.bank.upgrade.communication.domain.WelcomeLetterRepository;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * {@code POST /api/notifications/{id}/resend}: send a welcome letter again, for example after the customer
 * changed their email or says it never arrived. Allowed for ops admins and support (see ApiAccessRules).
 * The letter goes back to PENDING with a fresh set of attempts, and the request is recorded for the audit trail.
 */
@RestController
public class NotificationController {

    public record ResendAccepted(long id, String status) {}

    private final WelcomeLetterRepository letters;
    private final OutboxService outbox;
    private final BlockingJpa jpa;

    public NotificationController(WelcomeLetterRepository letters, OutboxService outbox, BlockingJpa jpa) {
        this.letters = letters;
        this.outbox = outbox;
        this.jpa = jpa;
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
