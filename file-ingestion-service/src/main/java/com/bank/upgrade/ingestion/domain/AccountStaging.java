package com.bank.upgrade.ingestion.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "ACCOUNT_STAGING")
@Getter
@Setter
@NoArgsConstructor
public class AccountStaging {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "staging_seq")
    @SequenceGenerator(name = "staging_seq", sequenceName = "ACCOUNT_STAGING_SEQ", allocationSize = 50)
    private Long id;

    @Column(name = "FILE_ID", nullable = false)
    private Long fileId;

    @Column(name = "ROW_NO", nullable = false)
    private int rowNo;

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

    @Column(precision = 18, scale = 2)
    private BigDecimal balance;

    @Column(name = "KYC_STATUS", length = 20)
    private String kycStatus;

    @Lob
    @Column(name = "RAW_ROW_JSON")
    private String rawRowJson;
}
