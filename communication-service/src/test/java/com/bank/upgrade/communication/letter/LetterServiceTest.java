package com.bank.upgrade.communication.letter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bank.upgrade.common.events.AccountUpgraded;
import com.bank.upgrade.common.messaging.NonRetryableEventException;
import com.bank.upgrade.communication.domain.LetterTemplate;
import com.bank.upgrade.communication.domain.LetterTemplateRepository;
import com.bank.upgrade.communication.domain.WelcomeLetter;
import com.bank.upgrade.communication.domain.WelcomeLetterRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Turning an upgrade into a queued welcome letter, with the database mocked and the real template renderer. */
class LetterServiceTest {

    final LetterTemplateRepository templates = mock(LetterTemplateRepository.class);
    final WelcomeLetterRepository letters = mock(WelcomeLetterRepository.class);
    final LetterService service = new LetterService(templates, letters, new LetterRenderer());

    private static LetterTemplate template(long id, String type, String subject) {
        var t = new LetterTemplate();
        t.setId(id);
        t.setTemplateCode("WELCOME_" + type);
        t.setAccountType(type);
        t.setVersionNo(2);
        t.setActive(true);
        t.setSubject(subject);
        t.setBodyHtml("<p>Dear <span th:text=\"${customerName}\">x</span>, account <b th:text=\"${maskedAccountNo}\">x</b></p>");
        return t;
    }

    private static AccountUpgraded upgrade(long requestId, String toType) {
        return new AccountUpgraded(requestId, "AC261110000123", "C1", "Asha <Rao>", "asha@example.com",
            toType.replace("_PLUS", ""), toType, LocalDate.of(2026, 11, 10), Instant.now());
    }

    @Test
    void anUpgradedAccountGetsALetterFromItsTypesActiveTemplate() {
        when(templates.findFirstByAccountTypeAndActiveTrueOrderByVersionNoDesc("SAVINGS_PLUS"))
            .thenReturn(Optional.of(template(4, "SAVINGS_PLUS", "Welcome to Savings Plus")));

        service.onUpgraded(upgrade(7, "SAVINGS_PLUS"), "file-3");

        ArgumentCaptor<WelcomeLetter> saved = ArgumentCaptor.forClass(WelcomeLetter.class);
        verify(letters).save(saved.capture());
        var letter = saved.getValue();
        assertThat(letter.getUpgradeRequestId()).isEqualTo(7);
        assertThat(letter.getRecipient()).isEqualTo("asha@example.com");
        assertThat(letter.getTemplateId()).isEqualTo(4);
        assertThat(letter.getSubject()).isEqualTo("Welcome to Savings Plus");
        assertThat(letter.getHtmlBody()).contains("Asha &lt;Rao&gt;").contains("0123").doesNotContain("AC261110000123");
        assertThat(letter.getStatus()).isEqualTo(WelcomeLetter.PENDING);
        assertThat(letter.getCorrelationId()).isEqualTo("file-3");
    }

    @Test
    void eachAccountTypeUsesItsOwnTemplate() {
        when(templates.findFirstByAccountTypeAndActiveTrueOrderByVersionNoDesc("CURRENT_PLUS"))
            .thenReturn(Optional.of(template(9, "CURRENT_PLUS", "Welcome to Current Plus")));

        service.onUpgraded(upgrade(8, "CURRENT_PLUS"), "file-3");

        ArgumentCaptor<WelcomeLetter> saved = ArgumentCaptor.forClass(WelcomeLetter.class);
        verify(letters).save(saved.capture());
        assertThat(saved.getValue().getSubject()).isEqualTo("Welcome to Current Plus");
    }

    @Test
    void theSameUpgradeAnnouncedTwiceGivesOneLetter() {
        when(letters.existsByUpgradeRequestId(7L)).thenReturn(true);

        service.onUpgraded(upgrade(7, "SAVINGS_PLUS"), "file-3");

        verify(letters, never()).save(any());
    }

    @Test
    void anAccountTypeWithoutATemplateCannotSucceedOnRetry() {
        when(templates.findFirstByAccountTypeAndActiveTrueOrderByVersionNoDesc(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.onUpgraded(upgrade(7, "GOLD_PLUS"), "file-3"))
            .isInstanceOf(NonRetryableEventException.class).hasMessageContaining("GOLD_PLUS");
    }

    @Test
    void theTemplateIsReadOnceAMinuteNotForEveryLetter() {
        when(templates.findFirstByAccountTypeAndActiveTrueOrderByVersionNoDesc("SAVINGS_PLUS"))
            .thenReturn(Optional.of(template(4, "SAVINGS_PLUS", "Welcome")));

        service.onUpgraded(upgrade(1, "SAVINGS_PLUS"), "f");
        service.onUpgraded(upgrade(2, "SAVINGS_PLUS"), "f");
        service.onUpgraded(upgrade(3, "SAVINGS_PLUS"), "f");

        verify(templates, times(1)).findFirstByAccountTypeAndActiveTrueOrderByVersionNoDesc("SAVINGS_PLUS");
        verify(letters, times(3)).save(any());
    }
}
