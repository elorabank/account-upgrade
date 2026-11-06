package com.bank.upgrade.communication.letter;

import com.bank.upgrade.common.events.AccountUpgraded;
import com.bank.upgrade.common.messaging.NonRetryableEventException;
import com.bank.upgrade.communication.domain.WelcomeLetter;
import com.bank.upgrade.communication.domain.WelcomeLetterRepository;
import com.bank.upgrade.communication.domain.LetterTemplate;
import com.bank.upgrade.communication.domain.LetterTemplateRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns an upgrade into a welcome letter and queues it for sending (PENDING). Runs inside the consumer's
 * transaction; the email is sent later by {@link com.bank.upgrade.communication.mail.LetterSender}, never while
 * the Kafka message is being handled, so a slow mail server cannot hold up the topic.
 */
public class LetterService {

    private static final Logger log = LoggerFactory.getLogger(LetterService.class);

    /** How long an active template is reused before it is looked up again: a new version is live within a minute. */
    private static final Duration TEMPLATE_CACHE = Duration.ofMinutes(1);

    private record Cached(LetterTemplate template, Instant until) {}

    private final LetterTemplateRepository templates;
    private final WelcomeLetterRepository letters;
    private final LetterRenderer renderer;
    private final Map<String, Cached> activeTemplates = new ConcurrentHashMap<>();

    public LetterService(LetterTemplateRepository templates, WelcomeLetterRepository letters, LetterRenderer renderer) {
        this.templates = templates;
        this.letters = letters;
        this.renderer = renderer;
    }

    public void onUpgraded(AccountUpgraded upgrade, String correlationId) {
        if (letters.existsByUpgradeRequestId(upgrade.requestId())) {
            log.info("Upgrade {} already has a letter, nothing to do", upgrade.requestId());
            return;
        }
        var template = activeTemplate(upgrade.toType())
            // Retrying will not create a template: park the event; replay it once the template exists
            .orElseThrow(() -> new NonRetryableEventException("No active letter template for account type " + upgrade.toType()));
        var rendered = renderer.render(template, upgrade);

        var letter = new WelcomeLetter();
        letter.setUpgradeRequestId(upgrade.requestId());
        letter.setAccountNo(upgrade.accountNo());
        letter.setCustomerId(upgrade.customerId());
        letter.setRecipient(upgrade.email());
        letter.setTemplateId(template.getId());
        letter.setSubject(rendered.subject());
        letter.setHtmlBody(rendered.html());
        letter.setCorrelationId(correlationId);
        letters.save(letter);
        log.debug("Letter for account {} queued ({} v{})", upgrade.accountNo(), template.getTemplateCode(), template.getVersionNo());
    }

    /** The highest active template for an account type, read from the database at most once a minute per type. */
    private Optional<LetterTemplate> activeTemplate(String accountType) {
        Instant now = Instant.now();
        Cached cached = activeTemplates.get(accountType);
        if (cached != null && cached.until().isAfter(now)) {
            return Optional.of(cached.template());
        }
        var found = templates.findFirstByAccountTypeAndActiveTrueOrderByVersionNoDesc(accountType);
        // A missing template is not cached, so adding one takes effect at once
        found.ifPresent(t -> activeTemplates.put(accountType, new Cached(t, now.plus(TEMPLATE_CACHE))));
        return found;
    }
}
