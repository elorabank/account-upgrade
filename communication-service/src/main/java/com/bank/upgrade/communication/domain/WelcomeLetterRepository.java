package com.bank.upgrade.communication.domain;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WelcomeLetterRepository extends JpaRepository<WelcomeLetter, Long>, JpaSpecificationExecutor<WelcomeLetter> {
    List<WelcomeLetter> findByAccountNo(String accountNo);

    long countByStatus(String status);

    boolean existsByUpgradeRequestId(Long upgradeRequestId);

    /** Rows of [status, count] for letters generated since {@code since}. */
    @Query("select l.status, count(l) from WelcomeLetter l where l.generatedAt >= :since group by l.status")
    List<Object[]> countByStatusSince(@Param("since") Instant since);

    /**
     * Locks the PENDING letters that are due, oldest first. SKIP LOCKED means a second instance sending at the same time
     * gets other letters instead of waiting or sending the same ones. The limit is in the inner select because Oracle
     * cannot combine FETCH FIRST with FOR UPDATE in one query block.
     */
    @Query(nativeQuery = true, value = """
        SELECT * FROM WELCOME_LETTER
        WHERE ID IN (SELECT ID FROM WELCOME_LETTER
                     WHERE STATUS = 'PENDING' AND (NEXT_ATTEMPT_AT IS NULL OR NEXT_ATTEMPT_AT <= :now)
                     ORDER BY ID FETCH FIRST :limit ROWS ONLY)
        ORDER BY ID
        FOR UPDATE SKIP LOCKED
        """)
    List<WelcomeLetter> lockDue(@Param("now") Instant now, @Param("limit") int limit);
}
