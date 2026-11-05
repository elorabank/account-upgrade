package com.bank.upgrade.audit.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {
    List<AuditEvent> findByAccountNoOrderByOccurredAtAsc(String accountNo);

    boolean existsByEventId(String eventId);
}
