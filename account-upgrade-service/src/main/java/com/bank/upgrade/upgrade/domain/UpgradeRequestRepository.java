package com.bank.upgrade.upgrade.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UpgradeRequestRepository extends JpaRepository<UpgradeRequest, Long>, JpaSpecificationExecutor<UpgradeRequest> {
    List<UpgradeRequest> findByRunId(String runId);

    long countByStatusAndBusinessDate(String status, LocalDate businessDate);

    @Query("select max(r.businessDate) from UpgradeRequest r")
    LocalDate findLatestBusinessDate();

    /** Rows of [status, count] for one business day. */
    @Query("select r.status, count(r) from UpgradeRequest r where r.businessDate = :date group by r.status")
    List<Object[]> countByStatus(@Param("date") LocalDate date);

    @Query("select r.accountNo from UpgradeRequest r where r.businessDate = :date")
    Set<String> findAccountNosByBusinessDate(@Param("date") LocalDate date);

    /**
     * Locks the oldest PENDING requests for this transaction, skipping any that another instance has locked and any
     * already tried in this run (those wait for the next run). The limit is in the inner select because Oracle cannot
     * combine FETCH FIRST with FOR UPDATE in one query block.
     */
    @Query(nativeQuery = true, value = """
        SELECT * FROM UPGRADE_REQUEST
        WHERE ID IN (SELECT ID FROM UPGRADE_REQUEST
                     WHERE STATUS = 'PENDING' AND (RUN_ID IS NULL OR RUN_ID <> :runId)
                     ORDER BY ID FETCH FIRST :limit ROWS ONLY)
        ORDER BY ID
        FOR UPDATE SKIP LOCKED
        """)
    List<UpgradeRequest> lockNextPending(@Param("runId") String runId, @Param("limit") int limit);

    /** Start of a run: requests a crashed run left IN_PROGRESS go back to PENDING. The scheduler lock means no other run is active. */
    @Modifying
    @Query("update UpgradeRequest r set r.status = 'PENDING', r.runId = null, r.version = r.version + 1 where r.status = 'IN_PROGRESS'")
    int reclaimInProgress();

    /** A run that stops early hands back what it claimed but did not process. */
    @Modifying
    @Query("update UpgradeRequest r set r.status = 'PENDING', r.version = r.version + 1 where r.status = 'IN_PROGRESS' and r.runId = :runId")
    int releaseRun(@Param("runId") String runId);
}
