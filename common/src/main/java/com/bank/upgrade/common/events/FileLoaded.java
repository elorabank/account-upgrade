package com.bank.upgrade.common.events;

import java.time.LocalDate;

/**
 * Data of the {@code FileLoaded} event on {@link Topics#FILE_LOADED}: an account file has been read into the
 * ingestion service's staging table and is ready to be upgraded.
 *
 * @param fileId       id in the ingestion service's FILE_INGESTION_LOG; the staging rows carry the same id
 * @param businessDate the day the file is for, taken from its name
 * @param totalRows    data rows in the file (loaded plus rejected)
 */
public record FileLoaded(
        long fileId, String fileName, LocalDate businessDate, int totalRows, int loadedRows, int rejectedRows) {}
