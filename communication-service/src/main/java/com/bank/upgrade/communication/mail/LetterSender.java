package com.bank.upgrade.communication.mail;

import com.bank.upgrade.common.correlation.CorrelationId;
import com.bank.upgrade.common.events.NotificationFailed;
import com.bank.upgrade.common.events.NotificationSent;
import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.messaging.OutboxService;
import com.bank.upgrade.communication.config.CommunicationProperties;
import com.bank.upgrade.communication.domain.WelcomeLetter;
import com.bank.upgrade.communication.domain.WelcomeLetterRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
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
 * the same letter), splits it over {@code connections} SMTP connections that send in parallel, each carrying its
 * share of the batch over one connection, and records each result with its event in the same transaction.
 * <p>
 * A mail server that is down or slow is retried with growing waits up to {@code maxAttempts}; an address the server
 * rejects fails at once. If the service stops after the server accepted an email but before the transaction
 * commits, that email is sent again later: delivery is at least once, never lost.
 * <p>
 * Metrics: {@code mail.letters} by result (sent, failed, retry), counted once the round has committed, and
 * {@code mail.round}, the time of each round that had letters to send.
 */
public class LetterSender {

    private static final Logger log = LoggerFactory.getLogger(LetterSender.class);

    private final WelcomeLetterRepository letters;
    private final JavaMailSender mail;
    private final OutboxService outbox;
    private final CommunicationProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry meters;
    private final Timer round;

    public LetterSender(WelcomeLetterRepository letters, JavaMailSender mail, OutboxService outbox,
            CommunicationProperties properties, PlatformTransactionManager transactionManager, Clock clock,
            MeterRegistry meters) {
        this.letters = letters;
        this.mail = mail;
        this.outbox = outbox;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.meters = meters;
        this.round = Timer.builder("mail.round").description("One round: lock due letters, send them, record the results")
            .register(meters);
    }

    /** One round. Returns how many letters were tried. */
    public int sendDue() {
        long started = System.nanoTime();
        Map<String, Integer> results = new HashMap<>();
        Integer tried = tx.execute(status -> {
            results.clear();
            var due = letters.lockDue(clock.instant(), properties.batchSize());
            if (due.isEmpty()) {
                return 0;
            }
            Map<WelcomeLetter, Exception> problems = sendAll(due);
            for (WelcomeLetter letter : due) {
                try (var scope = CorrelationId.open(letter.getCorrelationId())) {
                    letter.setAttempts(letter.getAttempts() + 1);
                    results.merge(record(letter, problems.get(letter)), 1, Integer::sum);
                }
            }
            return due.size();
        });
        if (tried != null && tried > 0) {
            round.record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
            results.forEach((result, n) -> Counter.builder("mail.letters").description("Welcome emails tried, by result")
                .tag("result", result).register(meters).increment(n));
        }
        return tried == null ? 0 : tried;
    }

    /**
     * Sends the batch over several connections at once and returns what went wrong per letter (absent = sent).
     * Never throws: a connection that fails takes its share of the batch with it, as one problem each.
     */
    private Map<WelcomeLetter, Exception> sendAll(List<WelcomeLetter> batch) {
        Map<WelcomeLetter, Exception> problems = new ConcurrentHashMap<>();
        Map<MimeMessage, WelcomeLetter> messages = new LinkedHashMap<>();
        for (WelcomeLetter letter : batch) {
            try {
                messages.put(message(letter), letter);
            } catch (MessagingException | UnsupportedEncodingException e) {
                problems.put(letter, new MailPreparationException("The message cannot be built: " + e.getMessage(), e));
            }
        }
        List<List<MimeMessage>> shares = split(new ArrayList<>(messages.keySet()), properties.connections());
        // Virtual threads: a thread per connection that mostly waits on the network costs next to nothing
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (List<MimeMessage> share : shares) {
                executor.submit(() -> {
                    try {
                        mail.send(share.toArray(MimeMessage[]::new));
                    } catch (MailSendException e) {
                        if (e.getFailedMessages().isEmpty()) {
                            share.forEach(m -> problems.put(messages.get(m), e));
                        } else {
                            // Only these failed; the others in the share went out
                            e.getFailedMessages().forEach((m, cause) -> problems.put(messages.get(m), cause));
                        }
                    } catch (RuntimeException e) {
                        share.forEach(m -> problems.put(messages.get(m), e));
                    }
                });
            }
        }
        return problems;
    }

    private MimeMessage message(WelcomeLetter letter) throws MessagingException, UnsupportedEncodingException {
        MimeMessage message = mail.createMimeMessage();
        var helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
        helper.setFrom(properties.from(), properties.fromName());
        helper.setTo(letter.getRecipient());
        helper.setSubject(letter.getSubject());
        helper.setText(letter.getHtmlBody(), true);
        message.setHeader("X-Letter-Id", String.valueOf(letter.getId()));
        return message;
    }

    private static List<List<MimeMessage>> split(List<MimeMessage> all, int parts) {
        int size = Math.max(1, (int) Math.ceil(all.size() / (double) Math.max(parts, 1)));
        List<List<MimeMessage>> shares = new ArrayList<>();
        for (int i = 0; i < all.size(); i += size) {
            shares.add(all.subList(i, Math.min(i + size, all.size())));
        }
        return shares;
    }

    /** Saves one letter's result; returns it for the metrics. */
    private String record(WelcomeLetter letter, Exception problem) {
        if (problem == null) {
            sent(letter);
            return "sent";
        } else if (permanent(problem)) {
            failed(letter, "The mail server refused the message: " + problem.getMessage());
            return "failed";
        } else if (letter.getAttempts() >= properties.maxAttempts()) {
            failed(letter, "Gave up after " + letter.getAttempts() + " attempts. Last error: " + problem.getMessage());
            return "failed";
        } else {
            retryLater(letter, problem);
            return "retry";
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
        log.debug("Welcome email for account {} sent (attempt {})", letter.getAccountNo(), letter.getAttempts());
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
    static boolean permanent(Exception e) {
        if (e instanceof MailParseException || e instanceof MailPreparationException) {
            return true;
        }
        if (e instanceof SendFailedException rejected) {
            return rejected.getInvalidAddresses() != null && rejected.getInvalidAddresses().length > 0;
        }
        if (e instanceof MailSendException send) {
            Map<Object, Exception> causes = new HashMap<>(send.getFailedMessages());
            return causes.values().stream().anyMatch(cause -> cause instanceof SendFailedException
                && ((SendFailedException) cause).getInvalidAddresses() != null
                && ((SendFailedException) cause).getInvalidAddresses().length > 0);
        }
        return e instanceof MailException && e.getCause() instanceof UnsupportedEncodingException;
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() <= 2000 ? s : s.substring(0, 2000);
    }
}
