package com.bank.upgrade.communication.letter;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.upgrade.common.events.AccountUpgraded;
import com.bank.upgrade.communication.domain.LetterTemplate;
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
        var r = renderer.render(template(), upgrade("Asha Rao"));

        assertThat(r.subject()).isEqualTo("Your account is now Savings Plus");
        assertThat(r.html())
            .contains("Dear <span>Asha Rao</span>")
            .contains("<b>ending 0123</b>")
            .contains("from <span>Savings</span>")
            .contains("10 November 2026")
            .doesNotContain("th:text");
    }

    @Test
    void customerDataCannotInjectMarkup() {
        var r = renderer.render(template(), upgrade("<script>alert('x')</script>Ann"));

        assertThat(r.html()).doesNotContain("<script>").contains("&lt;script&gt;");
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
    }
}
