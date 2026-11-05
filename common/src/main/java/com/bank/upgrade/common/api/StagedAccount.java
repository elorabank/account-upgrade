package com.bank.upgrade.common.api;

import java.math.BigDecimal;

/**
 * One account of a loaded file, as the ingestion service streams it to the upgrade service
 * ({@code GET /internal/files/{fileId}/accounts}, one JSON object per line). {@code id} increases within a file.
 */
public record StagedAccount(
        long id,
        int rowNo,
        String accountNo,
        String customerId,
        String customerName,
        String email,
        String currentType,
        BigDecimal balance,
        String kycStatus) {}
