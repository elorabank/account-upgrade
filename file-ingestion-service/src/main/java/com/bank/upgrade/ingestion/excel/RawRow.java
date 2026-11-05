package com.bank.upgrade.ingestion.excel;

import java.util.Map;

/** One data row as read from the sheet: Excel row number (the header is row 1) and cell text by normalised header. */
public record RawRow(int rowNo, Map<String, String> values) {}
