package com.bank.upgrade.upgrade;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.upgrade.common.events.EventEnvelope;
import com.bank.upgrade.common.events.FileLoaded;
import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.messaging.EventSubscription;
import com.bank.upgrade.common.persistence.outbox.OutboxEvent;
import com.bank.upgrade.common.persistence.outbox.OutboxEventRepository;
import com.bank.upgrade.test.OracleTestConfig;
import com.bank.upgrade.test.RedisTestConfig;
import com.bank.upgrade.upgrade.domain.UpgradeRequest;
import com.bank.upgrade.upgrade.domain.UpgradeRequestRepository;
import com.bank.upgrade.upgrade.gateway.AccountUpgradeGateway;
import com.bank.upgrade.upgrade.gateway.GatewayRejectedException;
import com.bank.upgrade.upgrade.gateway.GatewayUnavailableException;
import com.bank.upgrade.upgrade.gateway.UpgradeCommand;
import com.bank.upgrade.upgrade.run.UpgradeRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import com.bank.upgrade.common.api.StagedAccount;
import com.bank.upgrade.upgrade.staging.StagingSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Planning from a FileLoaded event and the nightly run, against Oracle with a programmable stand-in for the core system. */
@Import({OracleTestConfig.class, UpgradeFlowTest.FakesConfig.class, RedisTestConfig.class})
@SpringBootTest(properties = {
    "bank.upgrade.gateway=test",
    "bank.upgrade.schedule.enabled=false",
    "bank.upgrade.retry-backoff=0s",
    "bank.upgrade.batch-size=2",
    // One call at a time keeps the counts in these tests exact; UpgradeRunConcurrencyTest covers parallel calls
    "bank.upgrade.concurrency=1"
})
class UpgradeFlowTest {

    static final LocalDate DAY = LocalDate.of(2026, 11, 10);

    /** Succeeds unless a behaviour is queued for the account: an exception to throw, or null for success. */
    static class ProgrammableGateway implements AccountUpgradeGateway {
        final Map<String, Deque<RuntimeException>> behaviour = new ConcurrentHashMap<>();
        final List<UpgradeCommand> calls = new CopyOnWriteArrayList<>();

        void always(String account, RuntimeException e, int times) {
            var queue = new ArrayDeque<RuntimeException>();
            for (int i = 0; i < times; i++) queue.add(e);
            behaviour.put(account, queue);
        }

        @Override
        public Mono<Void> upgrade(UpgradeCommand command) {
            return Mono.fromRunnable(() -> {
                calls.add(command);
                var queue = behaviour.get(command.accountNo());
                RuntimeException next = queue == null ? null : queue.poll();
                if (next != null) throw next;
            });
        }

        long callsFor(String account) {
            return calls.stream().filter(c -> c.accountNo().equals(account)).count();
        }
    }

    /** The ingestion service's stream, kept in memory. */
    static class FakeStaging implements StagingSource {
        final Map<Long, List<StagedAccount>> files = new ConcurrentHashMap<>();

        @Override
        public Flux<StagedAccount> accounts(long fileId) {
            return Flux.fromIterable(files.getOrDefault(fileId, List.of()));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakesConfig {
        @Bean
        ProgrammableGateway gateway() {
            return new ProgrammableGateway();
        }

        @Bean
        @Primary
        FakeStaging fakeStaging() {
            return new FakeStaging();
        }
    }

    @Autowired UpgradeRequestRepository requests;
    @Autowired OutboxEventRepository outbox;
    @Autowired UpgradeRunner runner;
    @Autowired ProgrammableGateway gateway;
    @Autowired FakeStaging fakeStaging;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired org.springframework.beans.factory.ObjectProvider<EventSubscription> subscriptions;

    @BeforeEach
    void clean() {
        fakeStaging.files.clear();
        jdbc.update("DELETE FROM UPGRADE_REQUEST");
        jdbc.update("DELETE FROM OUTBOX_EVENT");
        gateway.calls.clear();
        gateway.behaviour.clear();
    }

    // --- planning -----------------------------------------------------------------------------------

    private long stagingId = 0;

    private void stage(long fileId, String account, String type, String balance, String kyc) {
        ++stagingId;
        fakeStaging.files.computeIfAbsent(fileId, k -> new ArrayList<>()).add(new StagedAccount(
            stagingId, (int) stagingId, account, "C-" + account, "Customer " + account, account + "@example.com",
            type, balance == null ? null : new BigDecimal(balance), kyc));
    }

    private void deliverFileLoaded(long fileId, int loadedRows) {
        var handler = subscriptions.stream().filter(s -> s.topic().equals(Topics.FILE_LOADED)).findFirst().orElseThrow().handler();
        var envelope = new EventEnvelope(1, "evt-" + fileId + "-" + Math.random(), "FileLoaded", "corr-file-" + fileId, Instant.now(),
            "system:file-ingestion-service", mapper.valueToTree(new FileLoaded(fileId, "accounts.xlsx", DAY, loadedRows, loadedRows, 0)));
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            try {
                handler.handle(envelope);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private UpgradeRequest request(String account) {
        return requests.findAll().stream().filter(r -> r.getAccountNo().equals(account)).findFirst().orElseThrow();
    }

    @Test
    void fileLoadedEventQueuesEligibleAccountsAndRejectsTheRest() {
        stage(101, "P1", "savings", "100.00", "VERIFIED");
        stage(101, "P2", "CURRENT", "2500", "VERIFIED");
        stage(101, "R1", "LOAN", "100", "VERIFIED");
        stage(101, "R2", "SAVINGS", "100", "PENDING");
        stage(101, "R3", "SAVINGS", "-5", "VERIFIED");
        stage(101, "R4", "SAVINGS", null, "VERIFIED");
        stage(999, "OTHER", "SAVINGS", "100", "VERIFIED");   // another file, must be ignored

        deliverFileLoaded(101, 6);

        assertThat(requests.count()).isEqualTo(6);
        assertThat(request("P1").getStatus()).isEqualTo(UpgradeRequest.PENDING);
        assertThat(request("P1").getTargetType()).isEqualTo("SAVINGS_PLUS");
        assertThat(request("P1").getSourceFileId()).isEqualTo(101);
        assertThat(request("P1").getBusinessDate()).isEqualTo(DAY);
        assertThat(request("P2").getTargetType()).isEqualTo("CURRENT_PLUS");
        assertThat(request("R1").getStatus()).isEqualTo(UpgradeRequest.REJECTED);
        assertThat(request("R1").getReason()).contains("no upgrade path for account type LOAN");
        assertThat(request("R2").getReason()).contains("KYC status is PENDING");
        assertThat(request("R3").getReason()).contains("below the minimum");
        assertThat(request("R4").getReason()).contains("balance is missing");
        assertThat(request("P1").getCorrelationId()).isEqualTo("corr-file-101");

        // Each rejection is announced, for the audit trail, with the file's correlation id
        var rejected = events(Topics.UPGRADE_REJECTED);
        assertThat(rejected).extracting(OutboxEvent::getMsgKey).containsExactlyInAnyOrder("R1", "R2", "R3", "R4");
        assertThat(rejected).extracting(OutboxEvent::getCorrelationId).containsOnly("corr-file-101");
    }

    @Test
    void theNightlyRunsEventsCarryTheCorrelationIdOfTheFileThatQueuedThem() {
        stage(104, "CO1", "SAVINGS", "10", "VERIFIED");
        deliverFileLoaded(104, 1);

        runner.runOnce();

        assertThat(events(Topics.ACCOUNT_UPGRADED)).singleElement()
            .extracting(OutboxEvent::getCorrelationId).isEqualTo("corr-file-104");
    }

    @Test
    void deliveringTheSameFileAgainDoesNotQueueAnythingTwice() {
        stage(102, "D1", "SAVINGS", "100", "VERIFIED");
        stage(102, "D2", "SAVINGS", "100", "VERIFIED");

        deliverFileLoaded(102, 2);
        deliverFileLoaded(102, 2);

        assertThat(requests.count()).isEqualTo(2);
    }

    @Test
    void largeFilesAreReadInSeveralPages() {
        for (int i = 1; i <= 1_234; i++) {
            stage(103, "BIG" + i, "SAVINGS", "10", "VERIFIED");
        }

        deliverFileLoaded(103, 1_234);

        assertThat(requests.count()).isEqualTo(1_234);
        assertThat(requests.countByStatusAndBusinessDate(UpgradeRequest.PENDING, DAY)).isEqualTo(1_234);
    }

    @Test
    void aFileLoadedEventWithoutFileIdGoesStraightToTheDeadLetterTopic() {
        var handler = subscriptions.stream().filter(s -> s.topic().equals(Topics.FILE_LOADED)).findFirst().orElseThrow().handler();
        var broken = new EventEnvelope(1, "evt-broken", "FileLoaded", "corr-1", Instant.now(), "system:test",
            mapper.createObjectNode().put("rows", 10));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handle(broken))
            .isInstanceOf(com.bank.upgrade.common.messaging.NonRetryableEventException.class);
        assertThat(requests.count()).isZero();
    }

    // --- the nightly run ----------------------------------------------------------------------------

    private void queue(String... accounts) {
        var list = new ArrayList<UpgradeRequest>();
        for (String account : accounts) {
            var r = new UpgradeRequest();
            r.setAccountNo(account);
            r.setCustomerId("C-" + account);
            r.setCustomerName("Customer " + account);
            r.setEmail(account + "@example.com");
            r.setCurrentType("SAVINGS");
            r.setTargetType("SAVINGS_PLUS");
            r.setBusinessDate(DAY);
            list.add(r);
        }
        requests.saveAll(list);
    }

    private List<OutboxEvent> events(String topic) {
        return outbox.findAll().stream().filter(e -> e.getTopic().equals(topic)).toList();
    }

    @Test
    void upgradesEveryPendingAccountAndPublishesOneEventEach() throws Exception {
        queue("U1", "U2", "U3", "U4", "U5");

        var summary = runner.runOnce();

        assertThat(summary.upgraded()).isEqualTo(5);
        assertThat(requests.findAll()).extracting(UpgradeRequest::getStatus).containsOnly(UpgradeRequest.UPGRADED);
        assertThat(gateway.calls).extracting(UpgradeCommand::idempotencyKey).contains("U1:2026-11-10");
        var published = events(Topics.ACCOUNT_UPGRADED);
        assertThat(published).hasSize(5).extracting(OutboxEvent::getMsgKey).containsExactlyInAnyOrder("U1", "U2", "U3", "U4", "U5");
        var data = mapper.readTree(published.get(0).getPayload()).get("data");
        assertThat(data.get("toType").asText()).isEqualTo("SAVINGS_PLUS");
        assertThat(data.get("email").asText()).endsWith("@example.com");
        assertThat(data.get("businessDate").asText()).isEqualTo("2026-11-10");
    }

    @Test
    void runningAgainDoesNothingBecauseNothingIsPending() {
        queue("N1");
        runner.runOnce();
        gateway.calls.clear();

        var second = runner.runOnce();

        assertThat(second.upgraded()).isZero();
        assertThat(gateway.calls).isEmpty();
        assertThat(events(Topics.ACCOUNT_UPGRADED)).hasSize(1);
    }

    @Test
    void aRefusalFromTheCoreSystemFailsOnlyThatAccount() {
        queue("OK1", "BAD", "OK2");
        gateway.always("BAD", new GatewayRejectedException("account is blocked"), 5);

        var summary = runner.runOnce();

        assertThat(summary.upgraded()).isEqualTo(2);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(request("BAD").getStatus()).isEqualTo(UpgradeRequest.FAILED);
        assertThat(request("BAD").getReason()).isEqualTo("account is blocked");
        assertThat(gateway.callsFor("BAD")).isEqualTo(1);   // no pointless retries
        assertThat(events(Topics.UPGRADE_FAILED)).hasSize(1).first().satisfies(e -> assertThat(e.getMsgKey()).isEqualTo("BAD"));
    }

    @Test
    void aShortOutageIsRiddenOutWithinTheRun() {
        queue("T1");
        gateway.always("T1", new GatewayUnavailableException("timeout"), 2);

        var summary = runner.runOnce();

        assertThat(summary.upgraded()).isEqualTo(1);
        assertThat(gateway.callsFor("T1")).isEqualTo(3);
        assertThat(request("T1").getStatus()).isEqualTo(UpgradeRequest.UPGRADED);
    }

    @Test
    void aLongerOutageCountsOneAttemptPerRunAndFailsAfterTheLimit() {
        queue("L1");
        gateway.always("L1", new GatewayUnavailableException("core system down"), 100);

        var first = runner.runOnce();
        assertThat(first.deferred()).isEqualTo(1);
        assertThat(gateway.callsFor("L1")).isEqualTo(3);   // tried the inline limit, not again in the same run
        assertThat(request("L1").getStatus()).isEqualTo(UpgradeRequest.PENDING);
        assertThat(request("L1").getAttemptCount()).isEqualTo(1);

        runner.runOnce();
        assertThat(request("L1").getAttemptCount()).isEqualTo(2);
        assertThat(request("L1").getStatus()).isEqualTo(UpgradeRequest.PENDING);

        var third = runner.runOnce();
        assertThat(third.failed()).isEqualTo(1);
        var done = request("L1");
        assertThat(done.getStatus()).isEqualTo(UpgradeRequest.FAILED);
        assertThat(done.getAttemptCount()).isEqualTo(3);
        assertThat(done.getReason()).contains("Gave up after 3 runs").contains("core system down");
        assertThat(events(Topics.UPGRADE_FAILED)).hasSize(1);
        assertThat(events(Topics.ACCOUNT_UPGRADED)).isEmpty();
    }

    @Test
    void anOutageStopsTheRunInsteadOfUsingUpEveryAccountsAttempts() {
        queue("A1", "A2", "A3", "A4", "A5", "A6", "A7");
        for (int i = 1; i <= 7; i++) {
            gateway.always("A" + i, new GatewayUnavailableException("down"), 100);
        }

        var summary = runner.runOnce();

        assertThat(summary.aborted()).isTrue();
        assertThat(summary.deferred()).isEqualTo(5);
        // The two accounts the run never reached (claims have no fixed order) are untouched and ready for the next run
        var all = java.util.stream.Stream.of("A1", "A2", "A3", "A4", "A5", "A6", "A7").map(this::request).toList();
        assertThat(all).allMatch(r -> UpgradeRequest.PENDING.equals(r.getStatus()));
        assertThat(all).filteredOn(r -> r.getAttemptCount() == 1).hasSize(5);
        assertThat(all).filteredOn(r -> r.getAttemptCount() == 0).hasSize(2)
            .allMatch(r -> r.getClaimedBy() == null && r.getClaimedUntil() == null);
    }

    @Test
    void requestsLeftInProgressByACrashedRunAreUpgradedByTheNextOne() {
        queue("S1");
        jdbc.update("UPDATE UPGRADE_REQUEST SET STATUS = 'IN_PROGRESS', RUN_ID = 'crashed-run' WHERE ACCOUNT_NO = 'S1'");

        var summary = runner.runOnce();

        assertThat(summary.upgraded()).isEqualTo(1);
        assertThat(request("S1").getStatus()).isEqualTo(UpgradeRequest.UPGRADED);
    }
}
