package com.bank.upgrade.communication;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.upgrade.common.persistence.processed.ProcessedEvent;
import com.bank.upgrade.common.persistence.processed.ProcessedEventRepository;
import com.bank.upgrade.communication.domain.*;
import com.bank.upgrade.test.OracleTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(OracleTestConfig.class)
@SpringBootTest
class LetterPersistenceTest {

    @Autowired LetterTemplateRepository templates;
    @Autowired WelcomeLetterRepository letters;
    @Autowired ProcessedEventRepository processed;

    @Test
    void activeTemplateIsFoundAndLargeHtmlSurvivesAsClob() {
        var t = new LetterTemplate();
        t.setTemplateCode("WELCOME_PREMIUM");
        t.setAccountType("PREMIUM");
        t.setVersionNo(1);
        t.setSubject("Welcome to Premium");
        t.setBodyHtml("<html>" + "x".repeat(20000) + "</html>");
        t.setActive(true);
        templates.save(t);

        var found = templates.findFirstByAccountTypeAndActiveTrueOrderByVersionNoDesc("PREMIUM").orElseThrow();
        assertThat(found.getBodyHtml()).hasSize(20013);

        var l = new WelcomeLetter();
        l.setUpgradeRequestId(9L);
        l.setAccountNo("ACC-9");
        l.setCustomerId("C9");
        l.setRecipient("c9@example.com");
        l.setTemplateId(found.getId());
        l.setSubject(found.getSubject());
        l.setHtmlBody(found.getBodyHtml());
        letters.save(l);
        var saved = letters.findByAccountNo("ACC-9");
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getStatus()).isEqualTo(WelcomeLetter.PENDING);
        assertThat(saved.get(0).getAttempts()).isZero();

        // Delivery is tracked on the same row
        var sent = saved.get(0);
        sent.setStatus(WelcomeLetter.SENT);
        sent.setAttempts(1);
        sent.setSentAt(java.time.Instant.now());
        letters.saveAndFlush(sent);
        assertThat(letters.countByStatus(WelcomeLetter.SENT)).isEqualTo(1);
    }

    @Test
    void consumedEventsAreRemembered() {
        processed.save(new ProcessedEvent("11111111-1111-1111-1111-111111111111", "account.upgraded"));
        assertThat(processed.existsById("11111111-1111-1111-1111-111111111111")).isTrue();
    }
}
