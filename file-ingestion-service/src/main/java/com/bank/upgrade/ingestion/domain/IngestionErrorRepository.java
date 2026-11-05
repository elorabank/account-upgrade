package com.bank.upgrade.ingestion.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionErrorRepository extends JpaRepository<IngestionError, Long> {
    long countByFileId(Long fileId);

    Page<IngestionError> findByFileId(Long fileId, Pageable page);
}
