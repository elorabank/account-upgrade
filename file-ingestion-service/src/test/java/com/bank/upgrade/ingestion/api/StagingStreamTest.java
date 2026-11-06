package com.bank.upgrade.ingestion.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bank.upgrade.common.api.StagedAccount;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Streaming a file's accounts page by page, each page starting after the last id of the one before. */
class StagingStreamTest {

    @Test
    @SuppressWarnings("unchecked")
    void everyAccountIsStreamedInIdOrderAcrossPages() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<Long> afterIds = new CopyOnWriteArrayList<>();
        // 7 accounts with ids 1..7, pages of 3
        when(jdbc.query(anyString(), any(RowMapper.class), eq(5L), any(), eq(3))).thenAnswer(inv -> {
            long after = inv.getArgument(3);
            afterIds.add(after);
            return LongStream.rangeClosed(after + 1, Math.min(after + 3, 7))
                .mapToObj(id -> new StagedAccount(id, (int) id + 1, "A" + id, "C" + id, "N", "e@x", "SAVINGS", null, "VERIFIED"))
                .toList();
        });

        var accounts = new StagingStream(jdbc, 3).accounts(5L).collectList().block();

        assertThat(accounts).extracting(StagedAccount::id).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(afterIds).containsExactly(0L, 3L, 6L);
    }
}
