package com.bank.upgrade.upgrade;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.upgrade.common.persistence.outbox.OutboxEvent;
import com.bank.upgrade.common.persistence.outbox.OutboxEventRepository;
import com.bank.upgrade.test.OracleTestConfig;
import com.bank.upgrade.test.RedisTestConfig;
import com.bank.upgrade.upgrade.domain.UpgradeRequest;
import com.bank.upgrade.upgrade.domain.UpgradeRequestRepository;
import com.bank.upgrade.upgrade.gateway.AccountUpgradeGateway;
import com.bank.upgrade.upgrade.gateway.GatewayUnavailableException;
import com.bank.upgrade.upgrade.gateway.UpgradeCommand;
import com.bank.upgrade.upgrade.run.UpgradeRunner;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Mono;

/** The nightly run with many core calls in flight at once: faster, bounded, and nothing done twice or lost. */
@Import({OracleTestConfig.class, UpgradeRunConcurrencyTest.SlowCoreConfig.class, RedisTestConfig.class})
@SpringBootTest(properties = {
    "bank.upgrade.gateway=test",
    "bank.upgrade.schedule.enabled=false",
    "bank.upgrade.retry-backoff=0s",
    "bank.upgrade.batch-size=25",
    "bank.upgrade.concurrency=20",
    "bank.upgrade.abort-after-consecutive-failures=5"
})
class UpgradeRunConcurrencyTest {

    static final LocalDate DAY = LocalDate.of(2026, 12, 31);

    /** A core system that takes 100 ms per call and remembers how many calls were in flight at most. */
    static class SlowCore implements AccountUpgradeGateway {
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final Set<String> called = ConcurrentHashMap.newKeySet();
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean down;

        @Override
        public Mono<Void> upgrade(UpgradeCommand command) {
            return Mono.defer(() -> {
                    calls.incrementAndGet();
                    called.add(command.accountNo());
                    maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    return Mono.delay(Duration.ofMillis(100));
                })
                // before the completion travels on, or the next call may start while this one still counts
                .doOnTerminate(inFlight::decrementAndGet)
                .then(Mono.defer(() -> down ? Mono.error(new GatewayUnavailableException("down")) : Mono.empty()));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SlowCoreConfig {
        @Bean
        SlowCore slowCore() {
            return new SlowCore();
        }
    }

    @Autowired UpgradeRunner runner;
    @Autowired UpgradeRequestRepository requests;
    @Autowired OutboxEventRepository outbox;
    @Autowired SlowCore core;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM UPGRADE_REQUEST");
        jdbc.update("DELETE FROM OUTBOX_EVENT");
        core.inFlight.set(0);
        core.maxInFlight.set(0);
        core.called.clear();
        core.calls.set(0);
        core.down = false;
    }

    private void queue(int count) {
        var list = new ArrayList<UpgradeRequest>();
        for (int i = 1; i <= count; i++) {
            var r = new UpgradeRequest();
            r.setAccountNo("CC" + i);
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

    @Test
    void manyAccountsAreUpgradedInParallelOnceEachWithOneEventEach() {
        queue(400);

        long start = System.nanoTime();
        var summary = runner.runOnce();
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(summary.upgraded()).isEqualTo(400);
        // 400 calls of 100 ms one after another would take 40 s; 20 at a time takes about 2 s plus database work
        assertThat(millis).isLessThan(15_000);
        assertThat(core.maxInFlight.get()).isBetween(10, 20);
        assertThat(core.calls.get()).isEqualTo(400);
        assertThat(core.called).hasSize(400);
        assertThat(requests.countByStatusAndBusinessDate(UpgradeRequest.UPGRADED, DAY)).isEqualTo(400);
        assertThat(outbox.findAll()).extracting(OutboxEvent::getMsgKey).hasSize(400).doesNotHaveDuplicates();
    }

    @Test
    void anOutageStopsNewCallsAndEveryClaimedAccountIsLeftReadyForTheNextRun() {
        queue(300);
        core.down = true;

        var summary = runner.runOnce();

        assertThat(summary.aborted()).isTrue();
        assertThat(summary.upgraded()).isZero();
        // Calls already in flight finish, so a few more than the threshold are tried; most are never called
        assertThat(core.called.size()).isLessThan(100);
        assertThat(summary.deferred()).isEqualTo(core.called.size());
        assertThat(requests.countByStatusAndBusinessDate(UpgradeRequest.IN_PROGRESS, DAY)).isZero();
        assertThat(requests.countByStatusAndBusinessDate(UpgradeRequest.PENDING, DAY)).isEqualTo(300);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM UPGRADE_REQUEST WHERE ATTEMPT_COUNT = 0", Integer.class))
            .isEqualTo(300 - core.called.size());
    }
}
