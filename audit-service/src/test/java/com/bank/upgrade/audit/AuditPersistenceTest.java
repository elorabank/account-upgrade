package com.bank.upgrade.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.upgrade.audit.domain.*;
import com.bank.upgrade.test.OracleTestConfig;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(OracleTestConfig.class)
@SpringBootTest
class AuditPersistenceTest {

    @Autowired AuditEventRepository repo;
    @Autowired JdbcTemplate jdbc;

    AuditEvent event(String eventId) {
        var e = new AuditEvent();
        e.setEventId(eventId);
        e.setTopic("account.upgraded");
        e.setEventType("AccountUpgraded");
        e.setAccountNo("ACC-7");
        e.setActor("system:upgrade-scheduler");
        e.setSummary("SAVINGS -> PREMIUM");
        e.setOccurredAt(Instant.now());
        return e;
    }

    @Test
    void sameEventIdIsStoredOnlyOnce() {
        repo.saveAndFlush(event("aaaaaaaa-0000-0000-0000-000000000001"));
        assertThat(repo.findByAccountNoOrderByOccurredAtAsc("ACC-7")).isNotEmpty();
        assertThatThrownBy(() -> repo.saveAndFlush(event("aaaaaaaa-0000-0000-0000-000000000001")))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rowsCannotBeUpdatedOrDeleted() {
        repo.saveAndFlush(event("aaaaaaaa-0000-0000-0000-000000000002"));
        assertThatThrownBy(() -> jdbc.update("update AUDIT_EVENT set ACTOR = 'someone-else'")).hasMessageContaining("insert-only");
        assertThatThrownBy(() -> jdbc.update("delete from AUDIT_EVENT")).hasMessageContaining("insert-only");
    }
}
