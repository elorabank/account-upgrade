package com.bank.upgrade.communication.mail;

import com.bank.upgrade.common.correlation.CorrelationId;
import com.bank.upgrade.common.events.NotificationFailed;
import com.bank.upgrade.common.events.NotificationSent;
import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.messaging.OutboxService;
import com.bank.upgrade.communication.config.CommunicationProperties;
import com.bank.upgrade.communication.domain.WelcomeLetter;
import com.bank.upgrade.communication.domain.WelcomeLetterRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends the letters that are due. Each round locks a batch of PENDING letters (SKIP LOCKED, so instances never send
 * the same letter), sends them, and records each result with its event in the same transaction.
 * <p>
 * A mail server that is down or slow is retried with growing waits up to {@code maxAttempts}; an address the server
 * rejects fails at once. If the service stops after the server accepted an email but before the transaction
 * commits, that email is sent again later: delivery is at least once, never lost.
 */
public class LetterSender {

    private static final Logger log = LoggerFactory.getLogger(LetterSender.class);

    private final WelcomeLetterRepository letters;
    private final JavaMailSender mail;
    private final OutboxService outbox;
    private final CommunicationProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public LetterSender(WelcomeLetterRepository letters, JavaMailSender mail, OutboxService outbox,
            CommunicationProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.letters = letters;
        this.mail = mail;
        this.outbox = outbox;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** One round. Returns how many letters were tried. */
    public int sendDue() {
        Integer tried = tx.execute(status -> {
            var due = letters.lockDue(clock.instant(), properties.batchSize());
            due.forEach(this::sendOne);
            return due.size();
        });
        return tried == null ? 0 : tried;
    }

    private void sendOne(WelcomeLetter letter) {
        try (var scope = CorrelationId.open(letter.getCorrelationId())) {
            letter.setAttempts(letter.getAttempts() + 1);
            try {
                mail.send(message -> {
                    var helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
                    helper.setFrom(properties.from(), properties.fromName());
                    helper.setTo(letter.getRecipient());
                    helper.setSubject(letter.getSubject());
                    helper.setText(letter.getHtmlBody(), true);
                    message.setHeader("X-Letter-Id", String.valueOf(letter.getId()));
                });
                sent(letter);
            } catch (MailException e) {
                if (permanent(e)) {
                    failed(letter, "The mail server refused the message: " + e.getMessage());
                } else if (letter.getAttempts() >= properties.maxAttempts()) {
                    failed(letter, "Gave up after " + letter.getAttempts() + " attempts. Last error: " + e.getMessage());
                } else {
                    retryLater(letter, e);
                }
            } catch (RuntimeException e) {
                // Anything unexpected counts as temporary for this letter only; the rest of the batch still commits
                if (letter.getAttempts() >= properties.maxAttempts()) {
                    failed(letter, "Gave up after " + letter.getAttempts() + " attempts. Last error: " + e);
                } else {
                    retryLater(letter, e);
                }
            }
        }
    }

    private void sent(WelcomeLetter letter) {
        letter.setStatus(WelcomeLetter.SENT);
        letter.setSentAt(clock.instant());
        letter.setErrorMessage(null);
        letter.setNextAttemptAt(null);
        outbox.publish(Topics.NOTIFICATION_SENT, letter.getAccountNo(), "NotificationSent", letter.getAccountNo(),
            new NotificationSent(letter.getId(), letter.getUpgradeRequestId(), letter.getAccountNo(), letter.getAttempts(), letter.getSentAt()),
            null);
        log.info("Welcome email for account {} sent (attempt {})", letter.getAccountNo(), letter.getAttempts());
    }

    private void failed(WelcomeLetter letter, String reason) {
        letter.setStatus(WelcomeLetter.FAILED);
        letter.setErrorMessage(truncate(reason));
        letter.setNextAttemptAt(null);
        outbox.publish(Topics.NOTIFICATION_FAILED, letter.getAccountNo(), "NotificationFailed", letter.getAccountNo(),
            new NotificationFailed(letter.getId(), letter.getUpgradeRequestId(), letter.getAccountNo(), letter.getErrorMessage(), letter.getAttempts()),
            null);
        log.error("Welcome email for account {} FAILED: {}", letter.getAccountNo(), reason);
    }

    private void retryLater(WelcomeLetter letter, Exception e) {
        Duration wait = properties.retryDelay().multipliedBy(1L << Math.min(letter.getAttempts() - 1, 10));
        letter.setNextAttemptAt(clock.instant().plus(wait));
        letter.setErrorMessage(truncate(e.getMessage()));
        log.warn("Welcome email for account {} not sent (attempt {}), retrying in {}: {}",
            letter.getAccountNo(), letter.getAttempts(), wait, e.getMessage());
    }

    /** True when sending the same message again cannot work: a malformed message, or recipients the server rejected. */
    static boolean permanent(MailException e) {
        if (e instanceof MailParseException || e instanceof MailPreparationException) {
            return true;
        }
        if (e instanceof MailSendException send) {
            for (Exception cause : send.getFailedMessages().values()) {
                if (cause instanceof SendFailedException rejected
                        && rejected.getInvalidAddresses() != null && rejected.getInvalidAddresses().length > 0) {
                    return true;
                }
                if (cause instanceof MessagingException && cause.getCause() instanceof UnsupportedEncodingException) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() <= 2000 ? s : s.substring(0, 2000);
    }
}
