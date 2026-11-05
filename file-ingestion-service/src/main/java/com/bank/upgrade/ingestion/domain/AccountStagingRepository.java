package com.bank.upgrade.ingestion.domain;

import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountStagingRepository extends JpaRepository<AccountStaging, Long> {
    long countByFileId(Long fileId);

    @Query("select s.accountNo from AccountStaging s where s.fileId = :fileId")
    Set<String> findAccountNosByFileId(@Param("fileId") Long fileId);
}
