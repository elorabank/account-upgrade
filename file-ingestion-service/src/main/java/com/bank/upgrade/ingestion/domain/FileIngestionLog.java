package com.bank.upgrade.ingestion.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "FILE_INGESTION_LOG")
@Getter
@Setter
@NoArgsConstructor
public class FileIngestionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "file_log_seq")
    @SequenceGenerator(name = "file_log_seq", sequenceName = "FILE_INGESTION_LOG_SEQ", allocationSize = 50)
    private Long id;

    @Column(name = "FILE_NAME", nullable = false)
    private String fileName;

    @Column(nullable = false, length = 64)
    private String checksum;

    @Column(name = "BUSINESS_DATE", nullable = false)
    private LocalDate businessDate;

    /** RUNNING, COMPLETED or FAILED. */
    @Column(nullable = false, length = 20)
    private String status = "RUNNING";

    @Column(name = "TOTAL_ROWS")
    private Integer totalRows;

    @Column(name = "LOADED_ROWS")
    private Integer loadedRows;

    @Column(name = "REJECTED_ROWS")
    private Integer rejectedRows;

    @Column(name = "ERROR_MESSAGE", length = 2000)
    private String errorMessage;

    @Column(name = "STARTED_AT", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "COMPLETED_AT")
    private Instant completedAt;
}
