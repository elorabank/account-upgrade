package com.bank.upgrade.ingestion.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "INGESTION_ERROR")
@Getter
@Setter
@NoArgsConstructor
public class IngestionError {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ing_error_seq")
    @SequenceGenerator(name = "ing_error_seq", sequenceName = "INGESTION_ERROR_SEQ", allocationSize = 50)
    private Long id;

    @Column(name = "FILE_ID", nullable = false)
    private Long fileId;

    @Column(name = "ROW_NO", nullable = false)
    private int rowNo;

    @Column(name = "ERROR_MESSAGE", nullable = false, length = 2000)
    private String errorMessage;

    @Lob
    @Column(name = "RAW_ROW_JSON")
    private String rawRowJson;

    @Column(name = "CREATED_AT", nullable = false)
    private Instant createdAt = Instant.now();
}
