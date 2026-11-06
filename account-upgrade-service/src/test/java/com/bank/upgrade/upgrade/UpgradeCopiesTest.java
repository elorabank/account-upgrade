package com.bank.upgrade.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bank.upgrade.common.messaging.OutboxService;
import com.bank.upgrade.test.OracleTestConfig;
import com.bank.upgrade.test.RedisTestConfig;
import com.bank.upgrade.upgrade.config.UpgradeProperties;
import com.bank.upgrade.upgrade.domain.UpgradeRequest;
import com.bank.upgrade.upgrade.domain.UpgradeRequestRepository;
import com.bank.upgrade.upgrade.gateway.AccountUpgradeGateway;
import com.bank.upgrade.upgrade.gateway.CoreCallLimiter;
import com.bank.upgrade.upgrade.gateway.RedisCoreCallLimiter;
import com.bank.upgrade.upgrade.gateway.UpgradeCommand;
import com.bank.upgrade.upgrade.run.UpgradeRunner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import reactor.core.publisher.Mono;

/**
 * Several copies of the upgrade service on one run, played by several runners in one JVM that share the database,
 * the core system and Redis, each with its own copy name, exactly as separate processes would.
 */
@Import({OracleTestConfig.class, RedisTestConfig.class, UpgradeCopiesTest.CoreConfig.class})
@SpringBootTest(properties = {
    "bank.upgrade.gateway=test",
    "bank.upgrade.schedule.enabled=false",
    "bank.upgrade.retry-backoff=0s",
    "bank.upgrade.batch-size=25",
    "bank.upgrade.concurrency=10",
    "bank.upgrade.lease=3s"
})
class UpgradeCopiesTest {

    static final LocalDate DAY = LocalDate.of(2027, 1, 15);

    /** A core system that takes 50 ms per call and remembers every call with its time. */
    static class RecordingCore implements AccountUpgradeGateway {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<Long> callTimes = new CopyOnWriteArrayList<>();
        final Map<String, Long> firstCallAt = new ConcurrentHashMap<>();
        volatile Duration latency = Duration.ofMillis(50);

        @Override
        public Mono<Void> upgrade(UpgradeCommand command) {
            return Mono.defer(() -> {
                long now = System.currentTimeMillis();
                calls.add(command.accountNo());
                callTimes.add(now);
                firstCallAt.putIfAbsent(command.accountNo(), now);
                return Mono.delay(latency).then();
            });
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CoreConfig {
        @Bean
        RecordingCore recordingCore() {
            return new RecordingCore();
        }
    }

    @Autowired UpgradeRequestRepository requests;
    @Autowired RecordingCore core;
    @Autowired OutboxService outbox;
    @Autowired UpgradeProperties properties;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReactiveStringRedisTemplate redis;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM UPGRADE_REQUEST");
        jdbc.update("DELETE FROM OUTBOX_EVENT");
        core.calls.clear();
        core.callTimes.clear();
        core.firstCallAt.clear();
        meters.clear();
        core.latency = Duration.ofMillis(50);
    }

    /** Each copy's metrics, as each process would have its own. */
    final Map<String, SimpleMeterRegistry> meters = new ConcurrentHashMap<>();

    private UpgradeRunner copy(String name, CoreCallLimiter limiter) {
        var registry = meters.computeIfAbsent(name, n -> new SimpleMeterRegistry());
        return new UpgradeRunner(requests, core, limiter, outbox, properties, transactionManager, jdbc, Clock.systemUTC(), registry, name);
    }

    private double count(String copy, String name, String... tags) {
        var counter = meters.get(copy).find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private void queue(String prefix, int count) {
        var list = new ArrayList<UpgradeRequest>();
        for (int i = 1; i <= count; i++) {
            var r = new UpgradeRequest();
            r.setAccountNo(prefix + i);
            r.setCustomerId("C" + i);
            r.setCustomerName("Customer " + i);
            r.setEmail("c" + i + "@example.com");
            r.setCurrentType("SAVINGS");
            r.setTargetType("SAVINGS_PLUS");
            r.setBusinessDate(DAY);
            list.add(r);
        }
        requests.saveAll(list);
    }

    /** Runs the copies at the same time on one run id, like every copy joining at 03:00. */
    private List<UpgradeRunner.Summary> runTogether(List<UpgradeRunner> copies) throws Exception {
        String runId = UUID.randomUUID().toString();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<UpgradeRunner.Summary>> results = copies.stream()
                .map(c -> threads.submit(() -> c.runIfIdle(runId).orElseThrow()))
                .toList();
            var summaries = new ArrayList<UpgradeRunner.Summary>();
            for (var result : results) {
                summaries.add(result.get());
            }
            return summaries;
        }
    }

    @Test
    void threeCopiesShareOneRunAndEveryAccountIsUpgradedOnce() throws Exception {
        queue("SH", 300);

        var summaries = runTogether(List.of(copy("copy-A", CoreCallLimiter.none()), copy("copy-B", CoreCallLimiter.none()),
            copy("copy-C", CoreCallLimiter.none())));

        assertThat(summaries).extracting(UpgradeRunner.Summary::upgraded).allMatch(n -> n > 0);
        assertThat(summaries.stream().mapToInt(UpgradeRunner.Summary::upgraded).sum()).isEqualTo(300);
        assertThat(core.calls).hasSize(300).doesNotHaveDuplicates();
        assertThat(requests.countByStatusAndBusinessDate(UpgradeRequest.UPGRADED, DAY)).isEqualTo(300);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM UPGRADE_REQUEST WHERE CLAIMED_BY IS NOT NULL", Integer.class))
            .as("no claims left behind").isZero();
        // Each copy's metrics add up to the whole run
        assertThat(List.of("copy-A", "copy-B", "copy-C").stream().mapToDouble(c -> count(c, "upgrade.requests", "result", "upgraded")).sum())
            .isEqualTo(300);
        assertThat(meters.get("copy-A").get("upgrade.requests.held").gauge().value()).isZero();
        assertThat(meters.get("copy-A").get("upgrade.core.call").tags("outcome", "ok").timer().count()).isPositive();
    }

    @Test
    void aDeadCopysClaimsAreTakenOverOnceItsLeasesRunOut() {
        queue("DEAD", 10);
        queue("LATE", 10);
        Instant lateUntil = Instant.now().plusSeconds(2);
        jdbc.update("UPDATE UPGRADE_REQUEST SET STATUS = 'IN_PROGRESS', RUN_ID = 'night', CLAIMED_BY = 'dead-copy', "
            + "CLAIMED_UNTIL = SYSTIMESTAMP - INTERVAL '1' MINUTE WHERE ACCOUNT_NO LIKE 'DEAD%'");
        // A copy that died a moment ago: its leases still run for 2 seconds
        jdbc.update("UPDATE UPGRADE_REQUEST SET STATUS = 'IN_PROGRESS', RUN_ID = 'night', CLAIMED_BY = 'dying-copy', "
            + "CLAIMED_UNTIL = ? WHERE ACCOUNT_NO LIKE 'LATE%'", lateUntil.atOffset(ZoneOffset.UTC));

        var summary = copy("copy-A", CoreCallLimiter.none()).runOnce();

        // Expired leases at once; the others only after they ran out, while copy-A waited instead of ending its run
        assertThat(summary.upgraded()).isEqualTo(20);
        assertThat(core.firstCallAt).hasSize(20);
        core.firstCallAt.forEach((account, at) -> {
            if (account.startsWith("DEAD")) {
                assertThat(at).as(account).isLessThan(lateUntil.toEpochMilli());
            } else {
                assertThat(at).as(account).isGreaterThanOrEqualTo(lateUntil.toEpochMilli());
            }
        });
        assertThat(requests.countByStatusAndBusinessDate(UpgradeRequest.UPGRADED, DAY)).isEqualTo(20);
        assertThat(count("copy-A", "upgrade.leases.taken.over")).isEqualTo(20);
    }

    @Test
    void aLiveCopysRequestsAreLeftAloneAndTheRunEndsWhenThatCopyIsDone() throws Exception {
        queue("FREE", 10);
        queue("LIVE", 10);
        jdbc.update("UPDATE UPGRADE_REQUEST SET STATUS = 'IN_PROGRESS', RUN_ID = 'night', CLAIMED_BY = 'live-copy', "
            + "CLAIMED_UNTIL = SYSTIMESTAMP + INTERVAL '5' MINUTE WHERE ACCOUNT_NO LIKE 'LIVE%'");
        var copyA = copy("copy-A", CoreCallLimiter.none());

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var run = threads.submit(copyA::runOnce);
            await().atMost(Duration.ofSeconds(10)).until(() -> core.calls.size() == 10);
            Thread.sleep(1_500);   // copy-A keeps looking (every 500 ms with a 3 s lease) but leaves LIVE alone
            assertThat(run.isDone()).as("copy-A waits while another copy holds requests").isFalse();

            // The live copy finishes its requests
            jdbc.update("UPDATE UPGRADE_REQUEST SET STATUS = 'UPGRADED', CLAIMED_BY = NULL, CLAIMED_UNTIL = NULL "
                + "WHERE ACCOUNT_NO LIKE 'LIVE%'");
            assertThat(run.get(5, TimeUnit.SECONDS).upgraded()).isEqualTo(10);
        }
        assertThat(core.calls).hasSize(10).allMatch(account -> account.startsWith("FREE"));
    }

    @Test
    void aWorkingCopyKeepsRenewingItsLeases() throws Exception {
        queue("LR", 60);
        core.latency = Duration.ofMillis(400);   // 60 calls, 10 at a time: about 2.5 s, close to the 3 s lease
        var copyA = copy("copy-A", CoreCallLimiter.none());

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var run = threads.submit(copyA::runOnce);
            Thread.sleep(2_000);
            // Mid-run: every request this copy holds has a lease reaching into the future
            Integer expired = jdbc.queryForObject("SELECT COUNT(*) FROM UPGRADE_REQUEST WHERE CLAIMED_BY = 'copy-A' "
                + "AND STATUS = 'IN_PROGRESS' AND CLAIMED_UNTIL < SYSTIMESTAMP", Integer.class);
            assertThat(expired).isZero();
            assertThat(run.get().upgraded()).isEqualTo(60);
        }
        assertThat(core.calls).hasSize(60).doesNotHaveDuplicates();
    }

    @Test
    void theSharedLimitHoldsAllCopiesTogetherToTheRate() throws Exception {
        queue("RL", 150);
        core.latency = Duration.ZERO;
        int perSecond = 50;

        var summaries = runTogether(List.of(
            copy("copy-A", new RedisCoreCallLimiter(redis, perSecond, Clock.systemUTC())),
            copy("copy-B", new RedisCoreCallLimiter(redis, perSecond, Clock.systemUTC()))));

        assertThat(summaries.stream().mapToInt(UpgradeRunner.Summary::upgraded).sum()).isEqualTo(150);
        Map<Long, AtomicInteger> perSecondSeen = new ConcurrentHashMap<>();
        core.callTimes.forEach(t -> perSecondSeen.computeIfAbsent(t / 1000, k -> new AtomicInteger()).incrementAndGet());
        // Counted in Redis per second; a call can land a moment after its second was counted, hence a little slack
        assertThat(perSecondSeen.values()).allMatch(n -> n.get() <= perSecond + 10);
        assertThat(perSecondSeen).as("150 calls at 50 a second need at least 3 seconds").hasSizeGreaterThanOrEqualTo(3);
    }
}
