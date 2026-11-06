package com.bank.upgrade.ingestion.job;

import com.bank.upgrade.common.retention.RetentionRule;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Deletes Spring Batch's records of load jobs that COMPLETED before the cutoff: their step contexts, steps, job
 * context, parameters and the execution, then each job instance left without executions. Records of failed jobs
 * stay, because the next load restarts a failed file from them.
 * <p>
 * At most 500 executions per call (Oracle allows 1000 values in an IN list). There are one or two a day, so the
 * runner stops after one call; if ever more were waiting, the next day's run would continue.
 */
public class BatchHistoryPurge implements RetentionRule.Purge {

    private static final int MAX_PER_CALL = 500;

    private final NamedParameterJdbcTemplate jdbc;
    private final ZoneId batchZone;

    /** @param batchZone the zone Spring Batch writes its local date-times in: this JVM's default */
    public BatchHistoryPurge(NamedParameterJdbcTemplate jdbc, ZoneId batchZone) {
        this.jdbc = jdbc;
        this.batchZone = batchZone;
    }

    @Override
    public int deleteBatch(Instant cutoff, int limit) {
        List<Long> executions = jdbc.queryForList("""
                SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION
                WHERE STATUS = 'COMPLETED' AND END_TIME < :before AND ROWNUM <= :limit""",
            Map.of("before", LocalDateTime.ofInstant(cutoff, batchZone), "limit", Math.min(limit, MAX_PER_CALL)), Long.class);
        if (executions.isEmpty()) {
            return 0;
        }
        Map<String, Object> ids = Map.of("ids", executions);
        List<Long> instances = jdbc.queryForList(
            "SELECT DISTINCT JOB_INSTANCE_ID FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID IN (:ids)", ids, Long.class);
        jdbc.update("""
            DELETE FROM BATCH_STEP_EXECUTION_CONTEXT WHERE STEP_EXECUTION_ID IN
              (SELECT STEP_EXECUTION_ID FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID IN (:ids))""", ids);
        jdbc.update("DELETE FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID IN (:ids)", ids);
        jdbc.update("DELETE FROM BATCH_JOB_EXECUTION_CONTEXT WHERE JOB_EXECUTION_ID IN (:ids)", ids);
        jdbc.update("DELETE FROM BATCH_JOB_EXECUTION_PARAMS WHERE JOB_EXECUTION_ID IN (:ids)", ids);
        jdbc.update("DELETE FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID IN (:ids)", ids);
        // A file restarted after a failure has more than one execution: its instance stays while any is left
        jdbc.update("""
            DELETE FROM BATCH_JOB_INSTANCE i WHERE i.JOB_INSTANCE_ID IN (:instances)
              AND NOT EXISTS (SELECT 1 FROM BATCH_JOB_EXECUTION e WHERE e.JOB_INSTANCE_ID = i.JOB_INSTANCE_ID)""",
            Map.of("instances", instances));
        return executions.size();
    }
}
