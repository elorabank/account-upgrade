package com.bank.upgrade.common.scheduling;

import com.bank.upgrade.common.correlation.CorrelationId;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * Starts a scheduled job by hand ("run now") under the same cluster lock its schedule uses, so a manual run and the
 * nightly run can never overlap, on this instance or another. The lock is taken before returning: the caller learns
 * at once whether the job started. The job itself runs in the background on its own thread.
 */
public class ManualRunner implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ManualRunner.class);

    private final LockProvider locks;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "manual-run");
        thread.setDaemon(true);
        return thread;
    });

    public ManualRunner(LockProvider locks) {
        this.locks = locks;
    }

    /**
     * @param lockName      the lock name of the job's {@code @SchedulerLock}
     * @param lockAtMostFor released after this long even if the job hangs (as on the schedule)
     * @return false if the job is already running somewhere
     */
    public boolean tryStart(String lockName, Duration lockAtMostFor, String correlationId, Runnable job) {
        var lock = locks.lock(new LockConfiguration(Instant.now(), lockName, lockAtMostFor, Duration.ZERO));
        if (lock.isEmpty()) {
            return false;
        }
        executor.submit(() -> {
            try (var scope = CorrelationId.open(correlationId)) {
                log.info("Manual run of {} started", lockName);
                job.run();
            } catch (RuntimeException e) {
                log.error("Manual run of {} failed", lockName, e);
            } finally {
                lock.get().unlock();
            }
        });
        return true;
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
