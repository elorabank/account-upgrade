package com.bank.upgrade.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.upgrade.common.persistence.outbox.OutboxEventRepository;
import com.bank.upgrade.ingestion.domain.FileIngestionLog;
import com.bank.upgrade.ingestion.domain.FileIngestionLogRepository;
import com.bank.upgrade.ingestion.domain.IngestionError;
import com.bank.upgrade.ingestion.domain.IngestionErrorRepository;
import com.bank.upgrade.test.OracleTestConfig;
import com.bank.upgrade.test.TestJwt;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The ops console's file endpoints and "run now", through the real security chain. */
@Import(OracleTestConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "bank.ingestion.schedule.enabled=false",
    // "run now" really runs the load: point it at a closed port so it fails fast instead of reaching an SFTP here
    "bank.ingestion.sftp.host=127.0.0.1",
    "bank.ingestion.sftp.port=1",
    "bank.ingestion.sftp.trust-any-host-key=true",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + TestJwt.ISSUER,
    "spring.security.oauth2.resourceserver.jwt.audiences=" + TestJwt.AUDIENCE
})
class IngestionOpsApiTest {

    static final TestJwt JWT = new TestJwt();

    @DynamicPropertySource
    static void jwks(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JWT::jwkSetUri);
    }

    @AfterAll
    static void stop() {
        JWT.stop();
    }

    @Autowired WebTestClient client;
    @Autowired FileIngestionLogRepository files;
    @Autowired IngestionErrorRepository errors;
    @Autowired OutboxEventRepository outbox;
    @Autowired LockProvider locks;
    @Autowired ObjectMapper mapper;

    private final String viewer = JWT.user("AU_OPS_VIEWER");
    private final String admin = JWT.user("AU_OPS_ADMIN");

    private FileIngestionLog file(String status, int errorRows) {
        var f = new FileIngestionLog();
        f.setFileName("accounts_" + UUID.randomUUID().toString().substring(0, 8) + ".xlsx");
        f.setChecksum(UUID.randomUUID().toString().replace("-", "") + "0".repeat(32));
        f.setBusinessDate(LocalDate.of(2026, 11, 20));
        f.setStatus(status);
        f.setLoadedRows(10);
        f.setRejectedRows(errorRows);
        f = files.save(f);
        for (int i = 1; i <= errorRows; i++) {
            var e = new IngestionError();
            e.setFileId(f.getId());
            e.setRowNo(i + 1);
            e.setErrorMessage("email is missing or not valid");
            e.setRawRowJson("{\"row\":" + i + "}");
            errors.save(e);
        }
        return f;
    }

    @Test
    void filesAreListedNewestFirstAndCanBeFilteredByStatus() {
        var older = file("COMPLETED", 0);
        var newer = file("FAILED", 0);

        client.get().uri("/api/files?size=200").headers(h -> h.setBearerAuth(viewer)).exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.items[0].id").isEqualTo(newer.getId().intValue())
            .jsonPath("$.items[?(@.id == " + older.getId() + ")].status").isEqualTo("COMPLETED");

        client.get().uri("/api/files?status=FAILED&size=200").headers(h -> h.setBearerAuth(viewer)).exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.items[*].status").value(statuses ->
                assertThat((java.util.List<Object>) statuses).containsOnly("FAILED"));
    }

    @Test
    void oneFileAndItsRejectedRowsInRowOrder() {
        var f = file("COMPLETED", 3);

        client.get().uri("/api/files/{id}", f.getId()).headers(h -> h.setBearerAuth(viewer)).exchange()
            .expectStatus().isOk().expectBody().jsonPath("$.fileName").isEqualTo(f.getFileName());
        client.get().uri("/api/files/{id}/errors?size=2", f.getId()).headers(h -> h.setBearerAuth(viewer)).exchange()
            .expectStatus().isOk().expectBody()
            .jsonPath("$.total").isEqualTo(3)
            .jsonPath("$.items.length()").isEqualTo(2)
            .jsonPath("$.items[0].rowNo").isEqualTo(2)
            .jsonPath("$.items[0].rawRow").isEqualTo("{\"row\":1}");
        client.get().uri("/api/files/987654321").headers(h -> h.setBearerAuth(viewer)).exchange().expectStatus().isNotFound();
    }

    @Test
    void runNowStartsTheLoadAndIsAudited() throws Exception {
        long before = outbox.count();

        client.post().uri("/api/jobs/ingestion/run").headers(h -> h.setBearerAuth(admin)).exchange()
            .expectStatus().isAccepted()
            .expectBody().jsonPath("$.started").isEqualTo(true).jsonPath("$.correlationId").isNotEmpty();

        var audit = outbox.findAll().stream().filter(e -> e.getTopic().equals("audit.user-action")).toList();
        assertThat(outbox.count()).isEqualTo(before + 1);
        var envelope = mapper.readTree(audit.get(audit.size() - 1).getPayload());
        assertThat(envelope.get("data").get("action").asText()).isEqualTo("RUN_INGESTION_JOB");
        assertThat(envelope.get("actor").asText()).doesNotStartWith("system:");
        Thread.sleep(500);   // let the background run (which fails on the closed SFTP port) release the lock
    }

    @Test
    void runNowRefusesWhileALoadIsRunning() {
        var held = locks.lock(new LockConfiguration(Instant.now(), IngestionScheduler.LOCK_NAME, Duration.ofMinutes(1), Duration.ZERO));
        assertThat(held).isPresent();
        try {
            client.post().uri("/api/jobs/ingestion/run").headers(h -> h.setBearerAuth(admin)).exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.started").isEqualTo(false);
        } finally {
            held.get().unlock();
        }
    }

    @Test
    void onlyOpsAdminsMayRunJobs() {
        client.post().uri("/api/jobs/ingestion/run").headers(h -> h.setBearerAuth(viewer)).exchange().expectStatus().isForbidden();
        client.post().uri("/api/jobs/ingestion/run").headers(h -> h.setBearerAuth(JWT.user("AU_SUPPORT"))).exchange().expectStatus().isForbidden();
        client.get().uri("/api/files").exchange().expectStatus().isUnauthorized();
    }
}
