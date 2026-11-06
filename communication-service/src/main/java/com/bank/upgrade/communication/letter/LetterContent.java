package com.bank.upgrade.communication.letter;

import com.bank.upgrade.communication.domain.LetterTemplate;
import com.bank.upgrade.communication.domain.LetterTemplateRepository;
import com.bank.upgrade.communication.domain.WelcomeLetter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The HTML of a letter, built when it is needed (to send it) from the template version the letter was written
 * from and the values kept on the letter. Letters written before letters became compact still carry their HTML and
 * get it back as it is.
 * <p>
 * Template versions never change (the database refuses it), so each one is read once and kept: there are only a
 * few, one or two per account type.
 */
public class LetterContent {

    private final LetterTemplateRepository templates;
    private final LetterRenderer renderer;
    private final Map<Long, LetterTemplate> byId = new ConcurrentHashMap<>();

    public LetterContent(LetterTemplateRepository templates, LetterRenderer renderer) {
        this.templates = templates;
        this.renderer = renderer;
    }

    /** @throws IllegalStateException if the letter's template version is missing, which the foreign key prevents */
    public String html(WelcomeLetter letter) {
        if (letter.getHtmlBody() != null) {
            return letter.getHtmlBody();
        }
        return renderer.html(template(letter.getTemplateId()), LetterRenderer.Values.of(letter));
    }

    private LetterTemplate template(Long id) {
        return byId.computeIfAbsent(id, key -> templates.findById(key)
            .orElseThrow(() -> new IllegalStateException("Letter template " + key + " does not exist")));
    }
}
