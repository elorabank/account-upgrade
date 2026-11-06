package com.bank.upgrade.communication.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.events.UserAction;
import com.bank.upgrade.common.messaging.OutboxService;
import com.bank.upgrade.communication.domain.WelcomeLetter;
import com.bank.upgrade.communication.domain.WelcomeLetterRepository;
import com.bank.upgrade.test.ApiTest;
import com.bank.upgrade.test.TestJwt;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The console's email endpoints and "resend", through the real security chain, with the database mocked. */
@ApiTest(NotificationController.class)
class NotificationApiTest {

    @MockitoBean WelcomeLetterRepository letters;
    @MockitoBean OutboxService outbox;
    @Autowired WebTestClient client;

    final String viewer = TestJwt.shared().user("AU_OPS_VIEWER");
    final String support = TestJwt.shared().user("AU_SUPPORT");
    final String auditor = TestJwt.shared().user("AU_AUDITOR");

    private static WelcomeLetter letter(long id, String status) {
        var l = new WelcomeLetter();
        l.setId(id);
        l.setUpgradeRequestId(100 + id);
        l.setAccountNo("AC" + id);
        l.setRecipient("c" + id + "@example.com");
        l.setSubject("Welcome");
        l.setStatus(status);
        l.setAttempts(WelcomeLetter.FAILED.equals(status) ? 5 : 1);
        l.setErrorMessage(WelcomeLetter.FAILED.equals(status) ? "Gave up after 5 attempts" : null);
        return l;
    }

    @Test
    @SuppressWarnings("unchecked")
    void theConsoleListsEmailsAndCountsThemByStatus() {
        when(letters.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(letter(1, "FAILED"))));
        when(letters.countByStatusSince(any())).thenReturn(List.of(new Object[] {"SENT", 40L}, new Object[] {"FAILED", 1L}));

        client.get().uri("/api/notifications?status=FAILED").headers(h -> h.setBearerAuth(viewer)).exchange()
            .expectStatus().isOk().expectBody()
            .jsonPath("$.items[0].accountNo").isEqualTo("AC1")
            .jsonPath("$.items[0].errorMessage").isEqualTo("Gave up after 5 attempts");
        client.get().uri("/api/notifications/summary").headers(h -> h.setBearerAuth(viewer)).exchange()
            .expectStatus().isOk().expectBody()
            .jsonPath("$.byStatus.SENT").isEqualTo(40)
            .jsonPath("$.byStatus.PENDING").isEqualTo(0)
            .jsonPath("$.total").isEqualTo(41);
    }

    @Test
    void supportCanResendALetterAndTheRequestIsAudited() {
        var failed = letter(2, WelcomeLetter.FAILED);
        when(letters.findById(2L)).thenReturn(Optional.of(failed));

        client.post().uri("/api/notifications/2/resend").headers(h -> h.setBearerAuth(support)).exchange()
            .expectStatus().isAccepted().expectBody().jsonPath("$.status").isEqualTo("PENDING");

        assertThat(failed.getStatus()).isEqualTo(WelcomeLetter.PENDING);
        assertThat(failed.getAttempts()).isZero();
        assertThat(failed.getErrorMessage()).isNull();
        verify(outbox).publish(eq(Topics.USER_ACTION), eq("AC2"), eq("UserAction"), eq("AC2"),
            argThat(a -> a instanceof UserAction u && u.action().equals("RESEND_WELCOME_EMAIL")), eq("pat@bank.test"));
    }

    @Test
    void resendIsForOpsAdminsAndSupportOnlyAndUnknownLettersAreNotFound() {
        client.post().uri("/api/notifications/3/resend").headers(h -> h.setBearerAuth(support)).exchange().expectStatus().isNotFound();
        client.post().uri("/api/notifications/3/resend").headers(h -> h.setBearerAuth(viewer)).exchange().expectStatus().isForbidden();
        client.post().uri("/api/notifications/3/resend").headers(h -> h.setBearerAuth(auditor)).exchange().expectStatus().isForbidden();
        verify(outbox, never()).publish(anyString(), anyString(), anyString(), anyString(), any(), any());
    }
}
