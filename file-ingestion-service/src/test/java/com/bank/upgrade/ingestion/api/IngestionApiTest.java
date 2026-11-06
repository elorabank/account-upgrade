package com.bank.upgrade.ingestion.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bank.upgrade.common.api.StagedAccount;
import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.events.UserAction;
import com.bank.upgrade.common.messaging.OutboxService;
import com.bank.upgrade.common.scheduling.ManualRunner;
import com.bank.upgrade.ingestion.IngestionScheduler;
import com.bank.upgrade.ingestion.IngestionService;
import com.bank.upgrade.ingestion.domain.FileIngestionLog;
import com.bank.upgrade.ingestion.domain.FileIngestionLogRepository;
import com.bank.upgrade.ingestion.domain.IngestionError;
import com.bank.upgrade.ingestion.domain.IngestionErrorRepository;
import com.bank.upgrade.test.ApiTest;
import com.bank.upgrade.test.TestJwt;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

/** The ingestion service's APIs (files for the console, run now, the staging stream) with the database mocked. */
@ApiTest({FilesController.class, IngestionJobController.class, StagingController.class})
class IngestionApiTest {

    @MockitoBean FileIngestionLogRepository files;
    @MockitoBean IngestionErrorRepository errors;
    @MockitoBean StagingStream stream;
    @MockitoBean ManualRunner runner;
    @MockitoBean IngestionService service;
    @MockitoBean OutboxService outbox;
    @Autowired WebTestClient client;

    final String viewer = TestJwt.shared().user("AU_OPS_VIEWER");
    final String admin = TestJwt.shared().user("AU_OPS_ADMIN");
    final String upgradeService = TestJwt.shared().service("account-upgrade-service", "staging.read");

    private static FileIngestionLog file(long id, String name, String status) {
        var f = new FileIngestionLog();
        f.setId(id);
        f.setFileName(name);
        f.setBusinessDate(LocalDate.of(2026, 11, 10));
        f.setStatus(status);
        return f;
    }

    @Test
    void filesAreListedNewestFirstAndCanBeFilteredByStatus() {
        when(files.findByStatus(eq("FAILED"), any())).thenReturn(new PageImpl<>(List.of(file(3, "accounts_20261112.xlsx", "FAILED"))));

        client.get().uri("/api/files?status=FAILED").headers(h -> h.setBearerAuth(viewer)).exchange()
            .expectStatus().isOk().expectBody()
            .jsonPath("$.total").isEqualTo(1)
            .jsonPath("$.items[0].fileName").isEqualTo("accounts_20261112.xlsx");

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(files).findByStatus(eq("FAILED"), page.capture());
        assertThat(page.getValue().getSort()).isEqualTo(Sort.by(Sort.Order.desc("id")));
    }

    @Test
    void oneFileAndItsRejectedRowsInRowOrder() {
        when(files.findById(3L)).thenReturn(Optional.of(file(3, "accounts_20261110.xlsx", "COMPLETED")));
        var error = new IngestionError();
        error.setRowNo(7);
        error.setErrorMessage("email is not valid");
        error.setRawRowJson("{\"Email\":\"nope\"}");
        when(errors.findByFileId(eq(3L), any())).thenReturn(new PageImpl<>(List.of(error)));

        client.get().uri("/api/files/3").headers(h -> h.setBearerAuth(viewer)).exchange()
            .expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("COMPLETED");
        client.get().uri("/api/files/3/errors").headers(h -> h.setBearerAuth(viewer)).exchange()
            .expectStatus().isOk().expectBody()
            .jsonPath("$.items[0].rowNo").isEqualTo(7)
            .jsonPath("$.items[0].rawRow").isEqualTo("{\"Email\":\"nope\"}");
        client.get().uri("/api/files/99").headers(h -> h.setBearerAuth(viewer)).exchange().expectStatus().isNotFound();

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(errors).findByFileId(eq(3L), page.capture());
        assertThat(page.getValue().getSort()).isEqualTo(Sort.by("rowNo"));
    }

    @Test
    void runNowStartsTheLoadUnderTheClusterLockAndIsAudited() throws Exception {
        ArgumentCaptor<Runnable> job = ArgumentCaptor.forClass(Runnable.class);
        when(runner.tryStart(eq(IngestionScheduler.LOCK_NAME), any(), anyString(), job.capture())).thenReturn(true);

        client.post().uri("/api/jobs/ingestion/run").headers(h -> h.setBearerAuth(admin)).exchange()
            .expectStatus().isAccepted().expectBody().jsonPath("$.started").isEqualTo(true);

        verify(outbox).publish(eq(Topics.USER_ACTION), eq("ingestion"), eq("UserAction"), eq("ingestion"),
            argThat(a -> a instanceof UserAction u && u.action().equals("RUN_INGESTION_JOB")), eq("pat@bank.test"));
        job.getValue().run();
        verify(service).runOnce();
    }

    @Test
    void runNowIsRefusedWhileALoadIsRunningAndForNonAdmins() {
        when(runner.tryStart(anyString(), any(), anyString(), any())).thenReturn(false);

        client.post().uri("/api/jobs/ingestion/run").headers(h -> h.setBearerAuth(admin)).exchange().expectStatus().isEqualTo(409);
        client.post().uri("/api/jobs/ingestion/run").headers(h -> h.setBearerAuth(viewer)).exchange().expectStatus().isForbidden();
        verify(outbox, never()).publish(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void aCompletedFileIsStreamedToTheUpgradeServiceAsNdjson() {
        when(files.findById(5L)).thenReturn(Optional.of(file(5, "accounts_20261110.xlsx", "COMPLETED")));
        when(stream.accounts(5L)).thenReturn(Flux.just(
            new StagedAccount(1, 2, "S1", "C1", "Asha", "a@example.com", "SAVINGS", new BigDecimal("10.50"), "VERIFIED"),
            new StagedAccount(2, 3, "S2", "C2", "Ben", "b@example.com", "CURRENT", null, null)));

        var accounts = client.get().uri("/internal/files/5/accounts").accept(MediaType.APPLICATION_NDJSON)
            .headers(h -> h.setBearerAuth(upgradeService)).exchange()
            .expectStatus().isOk()
            .returnResult(StagedAccount.class).getResponseBody().collectList().block();

        assertThat(accounts).extracting(StagedAccount::accountNo).containsExactly("S1", "S2");
    }

    @Test
    void aFileStillLoadingIsAConflictAndAnUnknownOneNotFound() {
        when(files.findById(6L)).thenReturn(Optional.of(file(6, "accounts_20261111.xlsx", "RUNNING")));

        client.get().uri("/internal/files/6/accounts").headers(h -> h.setBearerAuth(upgradeService)).exchange().expectStatus().isEqualTo(409);
        client.get().uri("/internal/files/7/accounts").headers(h -> h.setBearerAuth(upgradeService)).exchange().expectStatus().isNotFound();
    }

    @Test
    void onlyTheStagingScopeMayReadTheStream() {
        client.get().uri("/internal/files/5/accounts").headers(h -> h.setBearerAuth(admin)).exchange().expectStatus().isForbidden();
        client.get().uri("/internal/files/5/accounts").exchange().expectStatus().isUnauthorized();
    }
}
