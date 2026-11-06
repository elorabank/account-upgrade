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

    /** Events not yet published (an index range scan on STATUS, ID). */
    long countByStatus(String status);

    /** When the oldest unpublished event was written, or null when none is waiting. */
    @Query("select e.createdAt from OutboxEvent e where e.id = (select min(x.id) from OutboxEvent x where x.status = 'NEW')")
    Instant findOldestNewCreatedAt();

    @Modifying
    @Query("delete from OutboxEvent e where e.status = 'PUBLISHED' and e.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);
}
