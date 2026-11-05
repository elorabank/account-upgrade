package com.bank.upgrade.upgrade.staging;

import com.bank.upgrade.common.api.StagedAccount;
import reactor.core.publisher.Flux;

/** The accounts of a loaded file, owned by the ingestion service. */
public interface StagingSource {
    Flux<StagedAccount> accounts(long fileId);
}
