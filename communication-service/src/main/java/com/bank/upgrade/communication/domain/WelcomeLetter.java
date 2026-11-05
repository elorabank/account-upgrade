package com.bank.upgrade.communication.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One row per upgraded account: the letter, which is also the email body, and how its delivery went. */
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

    @Column(nullable = false, length = 300)
    private String subject;

    @Lob
    @Column(name = "HTML_BODY", nullable = false)
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
