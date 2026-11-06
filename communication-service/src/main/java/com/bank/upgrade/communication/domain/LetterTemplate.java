package com.bank.upgrade.communication.domain;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One version of a welcome letter. A version never changes once written (the database refuses it), because letters
 * point at it and are built from it when sent; a new wording is a new VERSION_NO, made ACTIVE.
 */
@Entity
@Table(name = "LETTER_TEMPLATE")
@Getter
@Setter
@NoArgsConstructor
public class LetterTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "letter_template_seq")
    @SequenceGenerator(name = "letter_template_seq", sequenceName = "LETTER_TEMPLATE_SEQ", allocationSize = 50)
    private Long id;

    @Column(name = "TEMPLATE_CODE", nullable = false, updatable = false, length = 60)
    private String templateCode;

    @Column(name = "ACCOUNT_TYPE", nullable = false, updatable = false, length = 30)
    private String accountType;

    @Column(name = "VERSION_NO", nullable = false, updatable = false)
    private int versionNo;

    @Column(nullable = false, updatable = false, length = 300)
    private String subject;

    @Lob
    @Column(name = "BODY_HTML", nullable = false, updatable = false)
    private String bodyHtml;

    /** Stored as NUMBER(1) 0/1 so it also works on Oracle versions without a BOOLEAN type. */
    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(nullable = false)
    private boolean active;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
