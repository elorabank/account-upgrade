package com.bank.upgrade.ingestion.job;

import com.bank.upgrade.ingestion.domain.AccountStagingRepository;
import com.bank.upgrade.ingestion.domain.IngestionErrorRepository;
import java.util.List;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

/**
 * Saves a chunk inside the step's transaction. Spring Batch stores the reader's position in the same transaction,
 * so rows and restart point commit together or not at all. The flush makes a database error fail this chunk here,
 * before Batch records the new position.
 */
public class RowWriter implements ItemWriter<ParsedRow> {

    private final AccountStagingRepository staging;
    private final IngestionErrorRepository errors;

    public RowWriter(AccountStagingRepository staging, IngestionErrorRepository errors) {
        this.staging = staging;
        this.errors = errors;
    }

    @Override
    public void write(Chunk<? extends ParsedRow> chunk) {
        List<? extends ParsedRow> items = chunk.getItems();
        staging.saveAllAndFlush(items.stream().filter(ParsedRow.Valid.class::isInstance).map(r -> ((ParsedRow.Valid) r).staging()).toList());
        errors.saveAllAndFlush(items.stream().filter(ParsedRow.Rejected.class::isInstance).map(r -> ((ParsedRow.Rejected) r).error()).toList());
    }
}
