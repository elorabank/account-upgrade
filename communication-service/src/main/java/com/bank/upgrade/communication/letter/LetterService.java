package com.bank.upgrade.communication.letter;

import com.bank.upgrade.common.events.AccountUpgraded;
import com.bank.upgrade.common.messaging.NonRetryableEventException;
import com.bank.upgrade.communication.domain.WelcomeLetter;
import com.bank.upgrade.communication.domain.WelcomeLetterRepository;
import com.bank.upgrade.communication.domain.LetterTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns an upgrade into a welcome letter and queues it for sending (PENDING). Runs inside the consumer's
 * transaction; the email is sent later by {@link com.bank.upgrade.communication.mail.LetterSender}, never while
 * the Kafka message is being handled, so a slow mail server cannot hold up the topic.
 */
public class LetterService {

    private static final Logger log = LoggerFactory.getLogger(LetterService.class);

    private final LetterTemplateRepository templates;
    private final WelcomeLetterRepository letters;
    private final LetterRenderer renderer;

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
        var template = templates.findFirstByAccountTypeAndActiveTrueOrderByVersionNoDesc(upgrade.toType())
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
        log.info("Letter for account {} queued ({} v{})", upgrade.accountNo(), template.getTemplateCode(), template.getVersionNo());
    }
}
