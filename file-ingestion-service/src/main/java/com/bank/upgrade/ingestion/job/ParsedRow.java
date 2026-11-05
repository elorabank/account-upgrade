package com.bank.upgrade.ingestion.job;

import com.bank.upgrade.ingestion.domain.AccountStaging;
import com.bank.upgrade.ingestion.domain.IngestionError;

/** What the processor made of a row: a staging row to save, or a rejection to record. */
public sealed interface ParsedRow {

    int rowNo();

    record Valid(int rowNo, AccountStaging staging) implements ParsedRow {}

    record Rejected(int rowNo, IngestionError error) implements ParsedRow {}
}
