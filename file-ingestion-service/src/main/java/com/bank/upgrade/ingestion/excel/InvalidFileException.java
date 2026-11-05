package com.bank.upgrade.ingestion.excel;

/** The file as a whole cannot be loaded (not a workbook, a required column is missing). Retrying the same file will not help. */
public class InvalidFileException extends RuntimeException {
    public InvalidFileException(String message) {
        super(message);
    }

    public InvalidFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
