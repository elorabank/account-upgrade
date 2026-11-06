package com.bank.upgrade.ingestion.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Deleting Spring Batch's records of old completed loads, with the database mocked. */
class BatchHistoryPurgeTest {

    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final BatchHistoryPurge purge = new BatchHistoryPurge(jdbc, ZoneOffset.UTC);
    final Instant cutoff = Instant.parse("2026-10-07T00:00:00Z");

    @Test
    void nothingOldMeansNothingDeleted() {
        when(jdbc.queryForList(anyString(), anyMap(), eq(Long.class))).thenReturn(List.of());

        assertThat(purge.deleteBatch(cutoff, 10_000)).isZero();
        verify(jdbc, never()).update(anyString(), anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void completedExecutionsGoChildTablesFirstThenTheirInstances() {
        when(jdbc.queryForList(anyString(), anyMap(), eq(Long.class))).thenAnswer(inv ->
            inv.<String>getArgument(0).contains("STATUS = 'COMPLETED'") ? List.of(11L, 12L) : List.of(5L));

        assertThat(purge.deleteBatch(cutoff, 10_000)).isEqualTo(2);

        // Only completed executions that ended before the cutoff, at most 500 at a time (Oracle's IN-list limit)
        verify(jdbc).queryForList(argThat(sql -> sql.contains("STATUS = 'COMPLETED'") && sql.contains("END_TIME < :before")),
            argThat((Map<String, ?> p) -> p.get("before").equals(LocalDateTime.of(2026, 10, 7, 0, 0)) && p.get("limit").equals(500)),
            eq(Long.class));
        var order = inOrder(jdbc);
        for (String table : List.of("BATCH_STEP_EXECUTION_CONTEXT", "BATCH_STEP_EXECUTION ", "BATCH_JOB_EXECUTION_CONTEXT",
                "BATCH_JOB_EXECUTION_PARAMS", "BATCH_JOB_EXECUTION ", "BATCH_JOB_INSTANCE")) {
            order.verify(jdbc).update(argThat((String sql) -> sql.contains("DELETE FROM " + table)), anyMap());
        }
        verify(jdbc).update(argThat((String sql) -> sql.contains("NOT EXISTS")),
            argThat((Map<String, ?> p) -> p.get("instances").equals(List.of(5L))));
    }
}
