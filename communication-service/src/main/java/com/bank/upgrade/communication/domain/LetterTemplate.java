package com.bank.upgrade.communication.domain;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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

    @Column(name = "TEMPLATE_CODE", nullable = false, length = 60)
    private String templateCode;

    @Column(name = "ACCOUNT_TYPE", nullable = false, length = 30)
    private String accountType;

    @Column(name = "VERSION_NO", nullable = false)
    private int versionNo;

    @Column(nullable = false, length = 300)
    private String subject;

    @Lob
    @Column(name = "BODY_HTML", nullable = false)
    private String bodyHtml;

    /** Stored as NUMBER(1) 0/1 so it also works on Oracle versions without a BOOLEAN type. */
    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(nullable = false)
    private boolean active;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
