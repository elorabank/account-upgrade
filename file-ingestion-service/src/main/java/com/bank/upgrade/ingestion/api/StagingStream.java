package com.bank.upgrade.ingestion.api;

import com.bank.upgrade.common.api.StagedAccount;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The staged accounts of one file as a stream. Pages are read by id (keyset), each on a boundedElastic thread
 * because JDBC blocks, and the next page is only read when the caller has used up the previous one: a slow reader
 * on the other end of the HTTP connection slows down the database reads instead of filling memory.
 */
public class StagingStream {

    private static final String PAGE_SQL = """
        SELECT ID, ROW_NO, ACCOUNT_NO, CUSTOMER_ID, CUSTOMER_NAME, EMAIL, CURRENT_TYPE, BALANCE, KYC_STATUS
        FROM ACCOUNT_STAGING
        WHERE FILE_ID = ? AND ID > ?
        ORDER BY ID
        FETCH FIRST ? ROWS ONLY
        """;

    private final JdbcTemplate jdbc;
    private final int pageSize;

    public StagingStream(JdbcTemplate jdbc, int pageSize) {
        this.jdbc = jdbc;
        this.pageSize = pageSize;
    }

    public Flux<StagedAccount> accounts(long fileId) {
        return page(fileId, 0)
            .expand(page -> page.size() < pageSize ? Mono.empty() : page(fileId, page.get(page.size() - 1).id()))
            .concatMapIterable(page -> page);
    }

    private Mono<List<StagedAccount>> page(long fileId, long afterId) {
        return Mono.fromCallable(() -> jdbc.query(PAGE_SQL,
                (rs, n) -> new StagedAccount(
                    rs.getLong("ID"),
                    rs.getInt("ROW_NO"),
                    rs.getString("ACCOUNT_NO"),
                    rs.getString("CUSTOMER_ID"),
                    rs.getString("CUSTOMER_NAME"),
                    rs.getString("EMAIL"),
                    rs.getString("CURRENT_TYPE"),
                    rs.getBigDecimal("BALANCE"),
                    rs.getString("KYC_STATUS")),
                fileId, afterId, pageSize))
            .subscribeOn(Schedulers.boundedElastic());
    }
}
