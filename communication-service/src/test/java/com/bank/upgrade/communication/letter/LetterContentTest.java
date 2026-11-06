package com.bank.upgrade.communication.letter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bank.upgrade.communication.domain.LetterTemplate;
import com.bank.upgrade.communication.domain.LetterTemplateRepository;
import com.bank.upgrade.communication.domain.WelcomeLetter;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Building a letter's HTML from its template version and stored values, with the template table mocked. */
class LetterContentTest {

    final LetterTemplateRepository templates = mock(LetterTemplateRepository.class);
    final LetterContent content = new LetterContent(templates, new LetterRenderer());

    private static LetterTemplate template(long id, String body) {
        var t = new LetterTemplate();
        t.setId(id);
        t.setSubject("Welcome");
        t.setBodyHtml(body);
        return t;
    }

    private static WelcomeLetter letter(long templateId, String accountNo) {
        var l = new WelcomeLetter();
        l.setTemplateId(templateId);
        l.setAccountNo(accountNo);
        l.setCustomerName("Asha Rao");
        l.setFromType("CURRENT");
        l.setToType("CURRENT_PLUS");
        l.setBusinessDate(LocalDate.of(2026, 11, 10));
        return l;
    }

    @Test
    void eachLetterIsBuiltFromTheVersionItWasWrittenFrom() {
        when(templates.findById(1L)).thenReturn(Optional.of(template(1, "<p>v1 <span th:text=\"${toTypeLabel}\">x</span></p>")));
        when(templates.findById(2L)).thenReturn(Optional.of(template(2, "<p>v2 <span th:text=\"${businessDate}\">x</span></p>")));

        assertThat(content.html(letter(1, "A1"))).isEqualTo("<p>v1 <span>Current Plus</span></p>");
        assertThat(content.html(letter(2, "A2"))).isEqualTo("<p>v2 <span>10 November 2026</span></p>");
    }

    @Test
    void aTemplateVersionIsReadOnceBecauseItNeverChanges() {
        when(templates.findById(1L)).thenReturn(Optional.of(template(1, "<p>Hi</p>")));

        for (int i = 0; i < 5; i++) {
            content.html(letter(1, "A" + i));
        }

        verify(templates, times(1)).findById(1L);
    }

    @Test
    void aLetterThatStillCarriesItsHtmlGetsItBackAsItIs() {
        var old = letter(1, "A1");
        old.setHtmlBody("<p>stored</p>");

        assertThat(content.html(old)).isEqualTo("<p>stored</p>");
    }

    @Test
    void aMissingTemplateVersionIsAnError() {
        when(templates.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> content.html(letter(7, "A1"))).hasMessageContaining("template 7");
    }
}
