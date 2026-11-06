package com.bank.upgrade.upgrade.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "UPGRADE_REQUEST")
@Getter
@Setter
@NoArgsConstructor
public class UpgradeRequest {

    public static final String PENDING = "PENDING";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String UPGRADED = "UPGRADED";
    public static final String REJECTED = "REJECTED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "upgrade_req_seq")
    @SequenceGenerator(name = "upgrade_req_seq", sequenceName = "UPGRADE_REQUEST_SEQ", allocationSize = 50)
    private Long id;

    @Column(name = "ACCOUNT_NO", nullable = false, length = 34)
    private String accountNo;

    @Column(name = "CUSTOMER_ID", nullable = false, length = 36)
    private String customerId;

    @Column(name = "CUSTOMER_NAME", nullable = false, length = 200)
    private String customerName;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "CURRENT_TYPE", nullable = false, length = 30)
    private String currentType;

    @Column(name = "TARGET_TYPE", length = 30)
    private String targetType;

    @Column(nullable = false, length = 20)
    private String status = PENDING;

    @Column(name = "ATTEMPT_COUNT", nullable = false)
    private int attemptCount;

    @Column(length = 1000)
    private String reason;

    @Column(name = "BUSINESS_DATE", nullable = false)
    private LocalDate businessDate;

    @Column(name = "SOURCE_FILE_ID")
    private Long sourceFileId;

    /** The run that claimed this request; several copies working on one run share it. */
    @Column(name = "RUN_ID", length = 36)
    private String runId;

    /** The service copy that holds this request while IN_PROGRESS. */
    @Column(name = "CLAIMED_BY", length = 100)
    private String claimedBy;

    /** The claim's lease: renewed while the copy works; after it, any copy may take the request over. */
    @Column(name = "CLAIMED_UNTIL")
    private Instant claimedUntil;

    /** The id of the business action that queued this request (the file's load), so its events can be traced back. */
    @Column(name = "CORRELATION_ID", length = 64)
    private String correlationId;

    @Version
    private long version;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
