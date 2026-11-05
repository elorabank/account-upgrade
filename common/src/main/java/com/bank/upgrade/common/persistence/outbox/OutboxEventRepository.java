package com.bank.upgrade.common.persistence.outbox;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findTop100ByStatusOrderByIdAsc(String status);

    /**
     * Locks the oldest unpublished events for this transaction. Oracle cannot combine FETCH FIRST with
     * FOR UPDATE in one query block, so the limit is applied in the inner select. SKIP LOCKED means a
     * second instance polling at the same time gets nothing instead of waiting or publishing the same rows.
     */
    @Query(nativeQuery = true, value = """
        SELECT * FROM OUTBOX_EVENT
        WHERE ID IN (SELECT ID FROM OUTBOX_EVENT WHERE STATUS = 'NEW' ORDER BY ID FETCH FIRST :limit ROWS ONLY)
        ORDER BY ID
        FOR UPDATE SKIP LOCKED
        """)
    List<OutboxEvent> lockNextBatch(@Param("limit") int limit);

    @Modifying
    @Query("delete from OutboxEvent e where e.status = 'PUBLISHED' and e.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);
}
