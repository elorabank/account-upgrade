package com.bank.upgrade.upgrade.domain;

import java.time.Instant;
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

    /** This copy is alive: push its leases forward. */
    @Modifying
    @Query("update UpgradeRequest r set r.claimedUntil = :until where r.claimedBy = :copy and r.status = 'IN_PROGRESS'")
    int renewClaims(@Param("copy") String copy, @Param("until") Instant until);

    /** A copy that stops early hands back what it claimed but did not process (the run id stays: not again this run). */
    @Modifying
    @Query("update UpgradeRequest r set r.status = 'PENDING', r.claimedBy = null, r.claimedUntil = null, r.version = r.version + 1 where r.claimedBy = :copy and r.status = 'IN_PROGRESS'")
    int releaseClaims(@Param("copy") String copy);

    /** Requests other copies hold (alive or not): a run is not over while there are any. */
    long countByStatusAndClaimedByNot(String status, String copy);

    /** Is some copy working on a run right now (a live lease)? */
    boolean existsByStatusAndClaimedUntilAfter(String status, Instant now);
}
