package com.bank.upgrade.communication.letter;

import com.bank.upgrade.common.events.AccountUpgraded;
import com.bank.upgrade.communication.domain.LetterTemplate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

/**
 * Fills a template from LETTER_TEMPLATE with the customer's details. Thymeleaf escapes every value in HTML mode,
 * so a customer name like {@code <b>Ann</b>} shows as text instead of becoming markup in the email.
 */
public class LetterRenderer {

    public record Rendered(String subject, String html) {}

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private final TemplateEngine html = engine(TemplateMode.HTML);
    private final TemplateEngine text = engine(TemplateMode.TEXT);

    public Rendered render(LetterTemplate template, AccountUpgraded upgrade) {
        var context = new Context(Locale.ENGLISH);
        context.setVariable("customerName", upgrade.customerName());
        context.setVariable("maskedAccountNo", mask(upgrade.accountNo()));
        context.setVariable("fromTypeLabel", label(upgrade.fromType()));
        context.setVariable("toTypeLabel", label(upgrade.toType()));
        context.setVariable("businessDate", upgrade.businessDate().format(DATE));
        return new Rendered(
            text.process(template.getSubject(), context).strip(),
            html.process(template.getBodyHtml(), context));
    }

    /** Only the last four characters: an email can be forwarded or read over a shoulder. */
    static String mask(String accountNo) {
        return "ending " + (accountNo.length() <= 4 ? accountNo : accountNo.substring(accountNo.length() - 4));
    }

    /** SAVINGS_PLUS becomes "Savings Plus". */
    static String label(String type) {
        return Arrays.stream(type.toLowerCase(Locale.ROOT).split("_"))
            .filter(w -> !w.isEmpty())
            .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1))
            .collect(Collectors.joining(" "));
    }

    private static TemplateEngine engine(TemplateMode mode) {
        var resolver = new StringTemplateResolver();
        resolver.setTemplateMode(mode);
        resolver.setCacheable(false);   // the template text itself is the key; templates are few and small
        var engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
