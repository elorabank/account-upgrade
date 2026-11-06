package com.bank.upgrade.communication.letter;

import com.bank.upgrade.common.events.AccountUpgraded;
import com.bank.upgrade.communication.domain.LetterTemplate;
import com.bank.upgrade.communication.domain.WelcomeLetter;
import java.time.LocalDate;
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
 * <p>
 * The same values always give the same letter, so a letter can be built again at any time from its template
 * version and the values kept in WELCOME_LETTER.
 */
public class LetterRenderer {

    /** What a template is filled with: the raw values, as stored; masking and labels are applied when filling. */
    public record Values(String customerName, String accountNo, String fromType, String toType, LocalDate businessDate) {

        public static Values of(AccountUpgraded upgrade) {
            return new Values(upgrade.customerName(), upgrade.accountNo(), upgrade.fromType(), upgrade.toType(),
                upgrade.businessDate());
        }

        public static Values of(WelcomeLetter letter) {
            return new Values(letter.getCustomerName(), letter.getAccountNo(), letter.getFromType(), letter.getToType(),
                letter.getBusinessDate());
        }
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private final TemplateEngine html = engine(TemplateMode.HTML);
    private final TemplateEngine text = engine(TemplateMode.TEXT);

    public String subject(LetterTemplate template, Values values) {
        return text.process(template.getSubject(), context(values)).strip();
    }

    public String html(LetterTemplate template, Values values) {
        return html.process(template.getBodyHtml(), context(values));
    }

    private static Context context(Values values) {
        var context = new Context(Locale.ENGLISH);
        context.setVariable("customerName", values.customerName());
        context.setVariable("maskedAccountNo", mask(values.accountNo()));
        context.setVariable("fromTypeLabel", label(values.fromType()));
        context.setVariable("toTypeLabel", label(values.toType()));
        context.setVariable("businessDate", values.businessDate().format(DATE));
        return context;
    }

    /** Only the last four characters: an email can be forwarded or read over a shoulder. */
    static String mask(String accountNo) {
        return "ending " + (accountNo.length() <= 4 ? accountNo : accountNo.substring(accountNo.length() - 4));
    }

    /** SAVINGS_PLUS becomes "Savings Plus". */
    static String label(String type) {
        if (type == null) {
            return "";
        }
        return Arrays.stream(type.toLowerCase(Locale.ROOT).split("_"))
            .filter(w -> !w.isEmpty())
            .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1))
            .collect(Collectors.joining(" "));
    }

    private static TemplateEngine engine(TemplateMode mode) {
        var resolver = new StringTemplateResolver();
        resolver.setTemplateMode(mode);
        // The template text itself is the cache key: each version is parsed once, a new version is simply a new key
        resolver.setCacheable(true);
        var engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
