package com.bank.upgrade.communication.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One row per upgraded account: what the welcome email says and how its delivery went. The email itself is built
 * when it is sent, from the template version ({@code templateId}) and the values kept here; see
 * {@link com.bank.upgrade.communication.letter.LetterContent}.
 */
@Entity
@Table(name = "WELCOME_LETTER")
@Getter
@Setter
@NoArgsConstructor
public class WelcomeLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "welcome_letter_seq")
    @SequenceGenerator(name = "welcome_letter_seq", sequenceName = "WELCOME_LETTER_SEQ", allocationSize = 50)
    private Long id;

    /** The upgrade this letter is for (from the AccountUpgraded event); one letter per upgrade. */
    @Column(name = "UPGRADE_REQUEST_ID", nullable = false, updatable = false)
    private Long upgradeRequestId;

    @Column(name = "ACCOUNT_NO", nullable = false, length = 34)
    private String accountNo;

    @Column(name = "CUSTOMER_ID", nullable = false, length = 36)
    private String customerId;

    @Column(nullable = false, length = 254)
    private String recipient;

    @Column(name = "TEMPLATE_ID", nullable = false)
    private Long templateId;

    /** Kept filled in, so the ops console can list letters without building each one. */
    @Column(nullable = false, length = 300)
    private String subject;

    @Column(name = "CUSTOMER_NAME", updatable = false, length = 200)
    private String customerName;

    @Column(name = "FROM_TYPE", updatable = false, length = 30)
    private String fromType;

    @Column(name = "TO_TYPE", updatable = false, length = 30)
    private String toType;

    @Column(name = "BUSINESS_DATE", updatable = false)
    private LocalDate businessDate;

    /** The finished HTML, only on letters written before letters became compact (changeset com-005); null since. */
    @Lob
    @Column(name = "HTML_BODY", updatable = false)
    private String htmlBody;

    public static final String PENDING = "PENDING";
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";

    /** PENDING until the email is accepted by the mail server, then SENT; FAILED once retries are used up. */
    @Column(nullable = false, length = 20)
    private String status = PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "ERROR_MESSAGE", length = 2000)
    private String errorMessage;

    @Column(name = "CORRELATION_ID", length = 64)
    private String correlationId;

    @Column(name = "GENERATED_AT", nullable = false, updatable = false)
    private Instant generatedAt = Instant.now();

    @Column(name = "SENT_AT")
    private Instant sentAt;

    /** When a PENDING letter is next due; null means now. */
    @Column(name = "NEXT_ATTEMPT_AT")
    private Instant nextAttemptAt;
}
