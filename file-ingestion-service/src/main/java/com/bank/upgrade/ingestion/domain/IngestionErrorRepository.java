package com.bank.upgrade.ingestion.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionErrorRepository extends JpaRepository<IngestionError, Long> {
    long countByFileId(Long fileId);
}
