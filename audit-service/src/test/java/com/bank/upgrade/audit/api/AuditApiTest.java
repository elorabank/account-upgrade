package com.bank.upgrade.audit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.bank.upgrade.audit.domain.AuditEvent;
import com.bank.upgrade.audit.domain.AuditEventRepository;
import com.bank.upgrade.test.ApiTest;
import com.bank.upgrade.test.TestJwt;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Searching the audit trail, through the real security chain, with the database mocked. */
@ApiTest(AuditQueryController.class)
class AuditApiTest {

    @MockitoBean AuditEventRepository events;
    @Autowired WebTestClient client;

    final TestJwt jwt = TestJwt.shared();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void oneEvent() {
        var e = new AuditEvent();
        e.setEventId("e1");
        e.setTopic("account.upgraded");
        e.setEventType("AccountUpgraded");
        e.setSourceService("account-upgrade-service");
        e.setAccountNo("AC1");
        e.setActor("system:account-upgrade-service");
        e.setCorrelationId("file-3");
        e.setSummary("SAVINGS -> SAVINGS_PLUS");
        e.setOccurredAt(Instant.parse("2026-11-10T21:30:00Z"));
        when(events.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(e)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void oneAccountsJourneyNewestFirstWithoutPersonalData() {
        client.get().uri("/api/audit/events?accountNo=AC1&size=1000").headers(h -> h.setBearerAuth(jwt.user("AU_AUDITOR"))).exchange()
            .expectStatus().isOk().expectBody()
            .jsonPath("$.items[0].eventType").isEqualTo("AccountUpgraded")
            .jsonPath("$.items[0].correlationId").isEqualTo("file-3")
            .jsonPath("$.items[0].email").doesNotExist()
            .jsonPath("$.size").isEqualTo(200);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(events).findAll(any(Specification.class), page.capture());
        assertThat(page.getValue().getSort()).isEqualTo(Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")));
        assertThat(page.getValue().getPageSize()).as("at most 200 a page").isEqualTo(200);
    }

    @Test
    void onlyAuditorsOpsAdminsAndTheAuditScopeMaySearch() {
        for (String allowed : List.of(jwt.user("AU_AUDITOR"), jwt.user("AU_OPS_ADMIN"), jwt.service("local-auditor", "audit.read"))) {
            client.get().uri("/api/audit/events").headers(h -> h.setBearerAuth(allowed)).exchange().expectStatus().isOk();
        }
        for (String refused : List.of(jwt.user("AU_OPS_VIEWER"), jwt.user("AU_SUPPORT"), jwt.service("perf-runner", "upgrade.admin"))) {
            client.get().uri("/api/audit/events").headers(h -> h.setBearerAuth(refused)).exchange().expectStatus().isForbidden();
        }
        client.get().uri("/api/audit/events").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void aBadDateIsABadRequest() {
        client.get().uri("/api/audit/events?from=yesterday").headers(h -> h.setBearerAuth(jwt.user("AU_AUDITOR"))).exchange()
            .expectStatus().isBadRequest();
    }
}
