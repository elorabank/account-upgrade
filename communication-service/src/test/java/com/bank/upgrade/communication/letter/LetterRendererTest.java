package com.bank.upgrade.communication.letter;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.upgrade.common.events.AccountUpgraded;
import com.bank.upgrade.communication.domain.LetterTemplate;
import com.bank.upgrade.communication.domain.WelcomeLetter;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class LetterRendererTest {

    private final LetterRenderer renderer = new LetterRenderer();

    private static LetterTemplate template() {
        var t = new LetterTemplate();
        t.setSubject("Your account is now [[${toTypeLabel}]]");
        t.setBodyHtml("<p>Dear <span th:text=\"${customerName}\">x</span>, account <b th:text=\"${maskedAccountNo}\">x</b> "
            + "moved from <span th:text=\"${fromTypeLabel}\">x</span> on <span th:text=\"${businessDate}\">x</span>.</p>");
        return t;
    }

    private static AccountUpgraded upgrade(String name) {
        return new AccountUpgraded(7, "AC261110000123", "C1", name, "a@example.com", "SAVINGS", "SAVINGS_PLUS",
            LocalDate.of(2026, 11, 10), Instant.now());
    }

    @Test
    void fillsSubjectAndBody() {
        var values = LetterRenderer.Values.of(upgrade("Asha Rao"));

        assertThat(renderer.subject(template(), values)).isEqualTo("Your account is now Savings Plus");
        assertThat(renderer.html(template(), values))
            .contains("Dear <span>Asha Rao</span>")
            .contains("<b>ending 0123</b>")
            .contains("from <span>Savings</span>")
            .contains("10 November 2026")
            .doesNotContain("th:text");
    }

    @Test
    void customerDataCannotInjectMarkup() {
        var html = renderer.html(template(), LetterRenderer.Values.of(upgrade("<script>alert('x')</script>Ann")));

        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @Test
    void aLetterRebuiltFromItsStoredValuesIsTheSameLetter() {
        var upgrade = upgrade("Asha Rao");
        var letter = new WelcomeLetter();
        letter.setAccountNo(upgrade.accountNo());
        letter.setCustomerName(upgrade.customerName());
        letter.setFromType(upgrade.fromType());
        letter.setToType(upgrade.toType());
        letter.setBusinessDate(upgrade.businessDate());

        assertThat(renderer.html(template(), LetterRenderer.Values.of(letter)))
            .isEqualTo(renderer.html(template(), LetterRenderer.Values.of(upgrade)));
    }

    @Test
    void onlyTheLastFourDigitsAreShown() {
        assertThat(LetterRenderer.mask("AC261110000123")).isEqualTo("ending 0123");
        assertThat(LetterRenderer.mask("123")).isEqualTo("ending 123");
    }

    @Test
    void typeCodesBecomeReadableLabels() {
        assertThat(LetterRenderer.label("CURRENT_PLUS")).isEqualTo("Current Plus");
        assertThat(LetterRenderer.label("SAVINGS")).isEqualTo("Savings");
        assertThat(LetterRenderer.label(null)).isEmpty();
    }
}
