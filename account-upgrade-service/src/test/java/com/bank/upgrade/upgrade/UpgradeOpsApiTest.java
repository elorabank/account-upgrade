package com.bank.upgrade.upgrade;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.upgrade.common.persistence.outbox.OutboxEventRepository;
import com.bank.upgrade.test.OracleTestConfig;
import com.bank.upgrade.test.RedisTestConfig;
import com.bank.upgrade.test.TestJwt;
import com.bank.upgrade.upgrade.domain.UpgradeRequest;
import com.bank.upgrade.upgrade.domain.UpgradeRequestRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The ops console's upgrade endpoints and "run now", through the real security chain. */
@Import({OracleTestConfig.class, RedisTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "bank.upgrade.schedule.enabled=false",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + TestJwt.ISSUER,
    "spring.security.oauth2.resourceserver.jwt.audiences=" + TestJwt.AUDIENCE
})
class UpgradeOpsApiTest {

    static final TestJwt JWT = new TestJwt();
    static final LocalDate DAY = LocalDate.of(2026, 12, 24);

    @DynamicPropertySource
    static void jwks(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JWT::jwkSetUri);
    }

    @AfterAll
    static void stop() {
        JWT.stop();
    }

    @Autowired WebTestClient client;
    @Autowired UpgradeRequestRepository requests;
    @Autowired OutboxEventRepository outbox;
    @Autowired JdbcTemplate jdbc;

    private final String viewer = JWT.user("AU_OPS_VIEWER");
    private final String admin = JWT.user("AU_OPS_ADMIN");

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM UPGRADE_REQUEST");
        jdbc.update("DELETE FROM OUTBOX_EVENT");
    }

    private UpgradeRequest request(String account, String status, LocalDate day) {
        var r = new UpgradeRequest();
        r.setAccountNo(account);
        r.setCustomerId("C-" + account);
        r.setCustomerName("Customer " + account);
        r.setEmail(account + "@example.com");
        r.setCurrentType("SAVINGS");
        r.setTargetType("SAVINGS_PLUS");
        r.setStatus(status);
        r.setBusinessDate(day);
        r.setSourceFileId(7L);
        if (UpgradeRequest.FAILED.equals(status)) {
            r.setAttemptCount(3);
            r.setReason("Gave up after 3 runs");
        }
        return requests.save(r);
    }

    @Test
    void searchFiltersCombine() {
        request("S1", UpgradeRequest.UPGRADED, DAY);
        request("S2", UpgradeRequest.FAILED, DAY);
        request("S3", UpgradeRequest.FAILED, DAY.minusDays(1));

        client.get().uri("/api/upgrades?status=FAILED&businessDate=" + DAY).headers(h -> h.setBearerAuth(viewer)).exchange()
            .expectStatus().isOk().expectBody()
            .jsonPath("$.total").isEqualTo(1)
            .jsonPath("$.items[0].accountNo").isEqualTo("S2")
            .jsonPath("$.items[0].customerName").isEqualTo("Customer S2")
            .jsonPath("$.items[0].email").doesNotExist();
        client.get().uri("/api/upgrades?accountNo=S3").headers(h -> h.setBearerAuth(viewer)).exchange()
            .expectStatus().isOk().expectBody().jsonPath("$.total").isEqualTo(1);
        client.get().uri("/api/upgrades?fileId=7").headers(h -> h.setBearerAuth(viewer)).exchange()
            .expectStatus().isOk().expectBody().jsonPath("$.total").isEqualTo(3);
    }

    @Test
    void summaryCountsTheLatestDayByDefault() {
        request("A1", UpgradeRequest.UPGRADED, DAY);
        request("A2", UpgradeRequest.UPGRADED, DAY);
        request("A3", UpgradeRequest.REJECTED, DAY);
        request("A4", UpgradeRequest.FAILED, DAY.minusDays(1));

        client.get().uri("/api/upgrades/summary").headers(h -> h.setBearerAuth(viewer)).exchange()
            .expectStatus().isOk().expectBody()
            .jsonPath("$.businessDate").isEqualTo(DAY.toString())
            .jsonPath("$.byStatus.UPGRADED").isEqualTo(2)
            .jsonPath("$.byStatus.REJECTED").isEqualTo(1)
            .jsonPath("$.byStatus.FAILED").isEqualTo(0)
            .jsonPath("$.total").isEqualTo(3);
        client.get().uri("/api/upgrades/summary?businessDate=" + DAY.minusDays(1)).headers(h -> h.setBearerAuth(viewer)).exchange()
            .expectStatus().isOk().expectBody().jsonPath("$.byStatus.FAILED").isEqualTo(1);
    }

    @Test
    void aFailedUpgradeCanBeRetriedByAnOpsAdmin() {
        var failed = request("R1", UpgradeRequest.FAILED, DAY);

        client.post().uri("/api/upgrades/{id}/retry", failed.getId()).headers(h -> h.setBearerAuth(admin)).exchange()
            .expectStatus().isAccepted().expectBody().jsonPath("$.status").isEqualTo("PENDING");

        var again = requests.findById(failed.getId()).orElseThrow();
        assertThat(again.getStatus()).isEqualTo(UpgradeRequest.PENDING);
        assertThat(again.getAttemptCount()).isZero();
        assertThat(again.getReason()).isNull();
        assertThat(outbox.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getTopic()).isEqualTo("audit.user-action");
            assertThat(e.getPayload()).contains("RETRY_UPGRADE");
        });
    }

    @Test
    void onlyFailedUpgradesCanBeRetried() {
        var upgraded = request("R2", UpgradeRequest.UPGRADED, DAY);

        client.post().uri("/api/upgrades/{id}/retry", upgraded.getId()).headers(h -> h.setBearerAuth(admin)).exchange()
            .expectStatus().isEqualTo(409).expectBody().jsonPath("$.status").isEqualTo("UPGRADED");
        client.post().uri("/api/upgrades/987654321/retry").headers(h -> h.setBearerAuth(admin)).exchange().expectStatus().isNotFound();
        client.post().uri("/api/upgrades/{id}/retry", upgraded.getId()).headers(h -> h.setBearerAuth(viewer)).exchange().expectStatus().isForbidden();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void runNowStartsTheUpgradeAndRefusesASecondAtTheSameTime() throws Exception {
        // Another copy is working on a run: it holds a live lease
        var held = request("HELD", UpgradeRequest.IN_PROGRESS, DAY);
        jdbc.update("UPDATE UPGRADE_REQUEST SET CLAIMED_BY = 'other-copy', CLAIMED_UNTIL = SYSTIMESTAMP + INTERVAL '5' MINUTE WHERE ID = ?", held.getId());
        client.post().uri("/api/jobs/upgrade/run").headers(h -> h.setBearerAuth(admin)).exchange()
            .expectStatus().isEqualTo(409);
        jdbc.update("DELETE FROM UPGRADE_REQUEST WHERE ID = ?", held.getId());

        var pending = request("RN" + UUID.randomUUID().toString().substring(0, 4), UpgradeRequest.PENDING, DAY);
        client.post().uri("/api/jobs/upgrade/run").headers(h -> h.setBearerAuth(admin)).exchange()
            .expectStatus().isAccepted().expectBody().jsonPath("$.started").isEqualTo(true);

        // The run happens in the background with the simulated core system
        for (int i = 0; i < 50 && !UpgradeRequest.UPGRADED.equals(requests.findById(pending.getId()).orElseThrow().getStatus()); i++) {
            Thread.sleep(100);
        }
        assertThat(requests.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(UpgradeRequest.UPGRADED);
    }
}
