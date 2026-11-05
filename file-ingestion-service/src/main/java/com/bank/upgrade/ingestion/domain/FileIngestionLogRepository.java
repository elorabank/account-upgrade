package com.bank.upgrade.ingestion.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileIngestionLogRepository extends JpaRepository<FileIngestionLog, Long> {
    boolean existsByChecksum(String checksum);

    Optional<FileIngestionLog> findByChecksum(String checksum);
}
