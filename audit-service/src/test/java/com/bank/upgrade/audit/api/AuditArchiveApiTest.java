package com.bank.upgrade.audit.api;

import static org.mockito.Mockito.when;

import com.bank.upgrade.audit.archive.AuditArchiveRepository;
import com.bank.upgrade.test.ApiTest;
import com.bank.upgrade.test.TestJwt;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Listing the archived months, through the real security chain, with the database mocked. */
@ApiTest(AuditArchiveController.class)
class AuditArchiveApiTest {

    @MockitoBean AuditArchiveRepository archives;
    @Autowired WebTestClient client;

    final TestJwt jwt = TestJwt.shared();

    @Test
    void auditorsSeeWhereEachOldMonthIsAndHowToCheckIt() {
        Instant at = Instant.parse("2026-02-05T23:30:00Z");
        when(archives.entries()).thenReturn(List.of(new AuditArchiveRepository.Entry(LocalDate.of(2025, 1, 1),
            AuditArchiveRepository.DROPPED, "audit-events-2025-01.jsonl.gz", 120_000L, "9f2c", 1L, 120_000L, at, at, at)));

        client.get().uri("/api/audit/archives").headers(h -> h.setBearerAuth(jwt.user("AU_AUDITOR"))).exchange()
            .expectStatus().isOk().expectBody()
            .jsonPath("$[0].month").isEqualTo("2025-01")
            .jsonPath("$[0].status").isEqualTo("DROPPED")
            .jsonPath("$[0].fileName").isEqualTo("audit-events-2025-01.jsonl.gz")
            .jsonPath("$[0].events").isEqualTo(120_000)
            .jsonPath("$[0].sha256").isEqualTo("9f2c");
    }

    @Test
    void sameAccessAsSearchingTheTrail() {
        when(archives.entries()).thenReturn(List.of());
        client.get().uri("/api/audit/archives").headers(h -> h.setBearerAuth(jwt.user("AU_OPS_ADMIN"))).exchange()
            .expectStatus().isOk();
        client.get().uri("/api/audit/archives").headers(h -> h.setBearerAuth(jwt.user("AU_SUPPORT"))).exchange()
            .expectStatus().isForbidden();
        client.get().uri("/api/audit/archives").exchange().expectStatus().isUnauthorized();
    }
}
