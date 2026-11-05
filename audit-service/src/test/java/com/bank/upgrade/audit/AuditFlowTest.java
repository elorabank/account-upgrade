package com.bank.upgrade.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.upgrade.audit.api.AuditQueryController.AuditPage;
import com.bank.upgrade.audit.domain.AuditEventRepository;
import com.bank.upgrade.common.events.EventEnvelope;
import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.messaging.EventSubscription;
import com.bank.upgrade.test.OracleTestConfig;
import com.bank.upgrade.test.TestJwt;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Events from every topic into the audit trail, then searched over HTTP with real tokens. The table is insert-only,
 * so each test uses its own account numbers and correlation ids instead of cleaning up.
 */
@Import(OracleTestConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + TestJwt.ISSUER,
    "spring.security.oauth2.resourceserver.jwt.audiences=" + TestJwt.AUDIENCE
})
class AuditFlowTest {

    static final TestJwt JWT = new TestJwt();

    @DynamicPropertySource
    static void jwks(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JWT::jwkSetUri);
    }

    @AfterAll
    static void stop() {
        JWT.stop();
    }

    @Autowired ObjectProvider<EventSubscription> subscriptions;
    @Autowired AuditEventRepository events;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired WebTestClient client;

    private final String auditor = JWT.user("AU_AUDITOR");

    private EventEnvelope deliver(String topic, String type, String correlationId, String actor, Instant at, Map<String, Object> data) {
        var handler = subscriptions.stream().filter(s -> s.topic().equals(topic)).findFirst().orElseThrow().handler();
        var envelope = new EventEnvelope(1, UUID.randomUUID().toString(), type, correlationId, at, actor, mapper.valueToTree(data));
        deliver(handler, envelope);
        return envelope;
    }

    private void deliver(com.bank.upgrade.common.messaging.EventHandler handler, EventEnvelope envelope) {
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            try {
                handler.handle(envelope);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private AuditPage search(String query, String token) {
        return client.get().uri("/api/audit/events?" + query)
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBody(AuditPage.class).returnResult().getResponseBody();
    }

    @Test
    void everyAuditedTopicHasASubscription() {
        var topics = subscriptions.stream().map(EventSubscription::topic).collect(Collectors.toSet());

        assertThat(topics).containsExactlyInAnyOrder(Topics.FILE_LOADED, Topics.ACCOUNT_UPGRADED, Topics.UPGRADE_REJECTED,
            Topics.UPGRADE_FAILED, Topics.NOTIFICATION_SENT, Topics.NOTIFICATION_FAILED, Topics.USER_ACTION);
    }

    @Test
    void oneAccountsJourneyCanBeFollowedWithoutPersonalData() {
        String corr = "corr-journey-" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-11-10T01:00:00Z");
        deliver(Topics.FILE_LOADED, "FileLoaded", corr, "system:file-ingestion-service", t0,
            Map.of("fileId", 1, "fileName", "accounts_20261110.xlsx", "businessDate", "2026-11-10", "totalRows", 2, "loadedRows", 2, "rejectedRows", 0));
        deliver(Topics.ACCOUNT_UPGRADED, "AccountUpgraded", corr, "system:account-upgrade-service", t0.plusSeconds(7200),
            Map.of("requestId", 5, "accountNo", "AUD-J1", "customerName", "Asha Rao", "email", "asha@example.com",
                "fromType", "SAVINGS", "toType", "SAVINGS_PLUS", "businessDate", "2026-11-10"));
        deliver(Topics.NOTIFICATION_SENT, "NotificationSent", corr, "system:communication-service", t0.plusSeconds(7300),
            Map.of("letterId", 9, "upgradeRequestId", 5, "accountNo", "AUD-J1", "attempts", 1));
        deliver(Topics.USER_ACTION, "UserAction", "corr-ui-" + UUID.randomUUID(), "ops.person@bank.example", t0.plusSeconds(9000),
            Map.of("action", "RESEND_WELCOME_EMAIL", "targetType", "WELCOME_LETTER", "targetId", "9", "accountNo", "AUD-J1"));

        var history = search("accountNo=AUD-J1", auditor);

        assertThat(history.total()).isEqualTo(3);
        assertThat(history.items()).extracting(i -> i.eventType())
            .containsExactly("UserAction", "NotificationSent", "AccountUpgraded");   // newest first
        var upgraded = history.items().get(2);
        assertThat(upgraded.summary()).isEqualTo("Upgraded SAVINGS -> SAVINGS_PLUS for 2026-11-10");
        assertThat(upgraded.sourceService()).isEqualTo("account-upgrade-service");
        assertThat(history.items().get(0).actor()).isEqualTo("ops.person@bank.example");
        assertThat(history.items()).allSatisfy(i -> assertThat(i.summary()).doesNotContain("Asha").doesNotContain("asha@"));

        // The whole business action across services, by correlation id
        var action = search("correlationId=" + corr, auditor);
        assertThat(action.items()).extracting(i -> i.eventType())
            .containsExactly("NotificationSent", "AccountUpgraded", "FileLoaded");
        assertThat(action.items().get(2).summary()).isEqualTo("accounts_20261110.xlsx for 2026-11-10: 2 rows, 2 loaded, 0 rejected");
    }

    @Test
    void theSameEventDeliveredAgainIsRecordedOnce() {
        var handler = subscriptions.stream().filter(s -> s.topic().equals(Topics.UPGRADE_REJECTED)).findFirst().orElseThrow().handler();
        var envelope = new EventEnvelope(1, UUID.randomUUID().toString(), "UpgradeRejected", "corr-dup", Instant.now(),
            "system:account-upgrade-service", mapper.valueToTree(Map.of("accountNo", "AUD-D1", "currentType", "LOAN", "reason", "no upgrade path")));

        deliver(handler, envelope);
        deliver(handler, envelope);

        assertThat(events.findByAccountNoOrderByOccurredAtAsc("AUD-D1")).hasSize(1);
    }

    @Test
    void searchFiltersByTimeAndTypeAndPagesTheResults() {
        Instant base = Instant.parse("2026-12-01T00:00:00Z");
        for (int i = 0; i < 5; i++) {
            deliver(Topics.NOTIFICATION_FAILED, "NotificationFailed", "corr-p", "system:communication-service",
                base.plus(i, ChronoUnit.HOURS), Map.of("accountNo", "AUD-P1", "attempts", 5, "reason", "mailbox full for p" + i + "@example.com"));
        }

        var firstPage = search("accountNo=AUD-P1&size=2", auditor);
        assertThat(firstPage.total()).isEqualTo(5);
        assertThat(firstPage.items()).hasSize(2);
        assertThat(firstPage.items().get(0).occurredAt()).isEqualTo(base.plus(4, ChronoUnit.HOURS));
        assertThat(firstPage.items()).allSatisfy(i -> assertThat(i.summary()).contains("[email removed]").doesNotContain("@example.com"));

        var window = search("accountNo=AUD-P1&eventType=NotificationFailed&from=2026-12-01T01:00:00Z&to=2026-12-01T03:00:00Z", auditor);
        assertThat(window.total()).isEqualTo(2);

        assertThat(search("accountNo=AUD-P1&size=100000", auditor).size()).isEqualTo(200);
    }

    @Test
    void onlyAuditorsOpsAdminsAndTheAuditScopeMaySearch() {
        search("accountNo=nobody", JWT.user("AU_OPS_ADMIN"));
        search("accountNo=nobody", JWT.service("reporting", "audit.read"));
        client.get().uri("/api/audit/events").headers(h -> h.setBearerAuth(JWT.user("AU_SUPPORT"))).exchange().expectStatus().isForbidden();
        client.get().uri("/api/audit/events").headers(h -> h.setBearerAuth(JWT.user("AU_OPS_VIEWER"))).exchange().expectStatus().isForbidden();
        client.get().uri("/api/audit/events").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void aBadDateIsABadRequest() {
        client.get().uri("/api/audit/events?from=yesterday").headers(h -> h.setBearerAuth(auditor)).exchange().expectStatus().isBadRequest();
    }
}
