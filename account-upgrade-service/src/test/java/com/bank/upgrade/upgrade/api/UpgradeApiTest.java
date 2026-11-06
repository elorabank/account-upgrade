package com.bank.upgrade.upgrade.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.events.UserAction;
import com.bank.upgrade.common.messaging.OutboxService;
import com.bank.upgrade.test.ApiTest;
import com.bank.upgrade.test.TestJwt;
import com.bank.upgrade.upgrade.domain.UpgradeRequest;
import com.bank.upgrade.upgrade.domain.UpgradeRequestRepository;
import com.bank.upgrade.upgrade.run.UpgradeRunner;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The ops console's upgrade endpoints and "run now", through the real security chain, with the database mocked. */
@ApiTest({UpgradesController.class, UpgradeJobController.class})
@Import(UpgradeApiTest.Clocks.class)
class UpgradeApiTest {

    static final LocalDate DAY = LocalDate.of(2026, 12, 24);

    @TestConfiguration
    static class Clocks {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @MockitoBean UpgradeRequestRepository requests;
    @MockitoBean OutboxService outbox;
    @MockitoBean UpgradeRunner runner;
    @Autowired WebTestClient client;

    final String viewer = TestJwt.shared().user("AU_OPS_VIEWER");
    final String admin = TestJwt.shared().user("AU_OPS_ADMIN");

    private static UpgradeRequest request(long id, String account, String status) {
        var r = new UpgradeRequest();
        r.setId(id);
        r.setAccountNo(account);
        r.setCustomerName("Customer " + account);
        r.setEmail(account + "@example.com");
        r.setCurrentType("SAVINGS");
        r.setTargetType("SAVINGS_PLUS");
        r.setStatus(status);
        r.setBusinessDate(DAY);
        r.setSourceFileId(7L);
        if (UpgradeRequest.FAILED.equals(status)) {
            r.setAttemptCount(3);
            r.setReason("Gave up after 3 runs");
        }
        return r;
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchShowsAPageOfUpgradesWithoutTheCustomersEmail() {
        when(requests.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(request(2, "S2", UpgradeRequest.FAILED)), PageRequest.of(0, 50), 1));

        client.get().uri("/api/upgrades?status=FAILED&businessDate=" + DAY).headers(h -> h.setBearerAuth(viewer)).exchange()
            .expectStatus().isOk().expectBody()
            .jsonPath("$.total").isEqualTo(1)
            .jsonPath("$.items[0].accountNo").isEqualTo("S2")
            .jsonPath("$.items[0].customerName").isEqualTo("Customer S2")
            .jsonPath("$.items[0].reason").isEqualTo("Gave up after 3 runs")
            .jsonPath("$.items[0].email").doesNotExist();
    }

    @Test
    void summaryCountsTheLatestDayByDefault() {
        when(requests.findLatestBusinessDate()).thenReturn(DAY);
        when(requests.countByStatus(DAY)).thenReturn(List.of(
            new Object[] {UpgradeRequest.UPGRADED, 2L}, new Object[] {UpgradeRequest.REJECTED, 1L}));

        client.get().uri("/api/upgrades/summary").headers(h -> h.setBearerAuth(viewer)).exchange()
            .expectStatus().isOk().expectBody()
            .jsonPath("$.businessDate").isEqualTo(DAY.toString())
            .jsonPath("$.byStatus.UPGRADED").isEqualTo(2)
            .jsonPath("$.byStatus.REJECTED").isEqualTo(1)
            .jsonPath("$.byStatus.FAILED").isEqualTo(0)
            .jsonPath("$.total").isEqualTo(3);
    }

    @Test
    void aFailedUpgradeCanBeRetriedByAnOpsAdminAndTheRetryIsAudited() {
        var failed = request(5, "R1", UpgradeRequest.FAILED);
        failed.setRunId("old-run");
        when(requests.findById(5L)).thenReturn(Optional.of(failed));

        client.post().uri("/api/upgrades/5/retry").headers(h -> h.setBearerAuth(admin)).exchange()
            .expectStatus().isAccepted().expectBody().jsonPath("$.status").isEqualTo("PENDING");

        assertThat(failed.getStatus()).isEqualTo(UpgradeRequest.PENDING);
        assertThat(failed.getAttemptCount()).isZero();
        assertThat(failed.getReason()).isNull();
        assertThat(failed.getRunId()).isNull();
        verify(outbox).publish(eq(Topics.USER_ACTION), eq("R1"), eq("UserAction"), eq("R1"),
            argThat(a -> a instanceof UserAction u && u.action().equals("RETRY_UPGRADE")), eq("pat@bank.test"));
    }

    @Test
    void onlyFailedUpgradesCanBeRetriedAndOnlyByOpsAdmins() {
        when(requests.findById(6L)).thenReturn(Optional.of(request(6, "R2", UpgradeRequest.UPGRADED)));

        client.post().uri("/api/upgrades/6/retry").headers(h -> h.setBearerAuth(admin)).exchange()
            .expectStatus().isEqualTo(409).expectBody().jsonPath("$.status").isEqualTo("UPGRADED");
        client.post().uri("/api/upgrades/404/retry").headers(h -> h.setBearerAuth(admin)).exchange().expectStatus().isNotFound();
        client.post().uri("/api/upgrades/6/retry").headers(h -> h.setBearerAuth(viewer)).exchange().expectStatus().isForbidden();
        client.post().uri("/api/upgrades/6/retry").exchange().expectStatus().isUnauthorized();
        verify(outbox, never()).publish(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void runNowStartsTheRunOnThisCopyAndAnnouncesItToTheOthers() {
        when(runner.busy()).thenReturn(false);
        when(requests.existsByStatusAndClaimedUntilAfter(eq(UpgradeRequest.IN_PROGRESS), any())).thenReturn(false);

        String runId = client.post().uri("/api/jobs/upgrade/run").headers(h -> h.setBearerAuth(admin)).exchange()
            .expectStatus().isAccepted()
            .expectBody(UpgradeJobController.JobStart.class).returnResult().getResponseBody().runId();

        verify(outbox).publish(eq(Topics.USER_ACTION), anyString(), eq("UserAction"), anyString(),
            argThat(a -> a instanceof UserAction u && u.action().equals("RUN_UPGRADE_JOB")), eq("pat@bank.test"));
        verify(outbox).publish(eq(Topics.UPGRADE_RUN_REQUESTED), eq(runId), anyString(), anyString(), any(), anyString());
        verify(runner, timeout(2000)).runIfIdle(runId);
    }

    @Test
    void runNowIsRefusedWhileAnyCopyIsWorkingOnARun() {
        when(requests.existsByStatusAndClaimedUntilAfter(eq(UpgradeRequest.IN_PROGRESS), any())).thenReturn(true);
        client.post().uri("/api/jobs/upgrade/run").headers(h -> h.setBearerAuth(admin)).exchange().expectStatus().isEqualTo(409);

        when(requests.existsByStatusAndClaimedUntilAfter(eq(UpgradeRequest.IN_PROGRESS), any())).thenReturn(false);
        when(runner.busy()).thenReturn(true);
        client.post().uri("/api/jobs/upgrade/run").headers(h -> h.setBearerAuth(admin)).exchange().expectStatus().isEqualTo(409);

        client.post().uri("/api/jobs/upgrade/run").headers(h -> h.setBearerAuth(viewer)).exchange().expectStatus().isForbidden();
        verify(outbox, never()).publish(anyString(), anyString(), anyString(), anyString(), any(), any());
        verify(runner, never()).runIfIdle(anyString());
    }
}
