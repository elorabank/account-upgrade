package com.bank.upgrade.upgrade.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bank.upgrade.common.api.StagedAccount;
import com.bank.upgrade.common.correlation.CorrelationId;
import com.bank.upgrade.common.events.FileLoaded;
import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.messaging.OutboxService;
import com.bank.upgrade.test.NoOpTransactionManager;
import com.bank.upgrade.upgrade.config.UpgradeProperties;
import com.bank.upgrade.upgrade.domain.UpgradeRequest;
import com.bank.upgrade.upgrade.domain.UpgradeRequestRepository;
import com.bank.upgrade.upgrade.staging.StagingSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** Planning a loaded file into upgrade requests, with the ingestion service, database and outbox mocked. */
class UpgradePlannerTest {

    static final LocalDate DAY = LocalDate.of(2027, 2, 1);

    final StagingSource staging = mock(StagingSource.class);
    final UpgradeRequestRepository requests = mock(UpgradeRequestRepository.class);
    final OutboxService outbox = mock(OutboxService.class);
    final List<UpgradeRequest> saved = new CopyOnWriteArrayList<>();
    final Set<String> alreadyQueued = new HashSet<>();
    final AtomicInteger saveCalls = new AtomicInteger();
    final List<String> correlationIdsAtPublish = new CopyOnWriteArrayList<>();

    final EligibilityRules rules = new EligibilityRules(new UpgradeProperties.Rules(null, "VERIFIED", BigDecimal.ZERO));
    final UpgradePlanner planner = new UpgradePlanner(staging, rules, requests, outbox, new NoOpTransactionManager());

    @BeforeEach
    @SuppressWarnings("unchecked")
    void mocks() {
        AtomicLong ids = new AtomicLong();
        when(requests.findAccountNosByBusinessDate(DAY)).thenAnswer(inv -> Set.copyOf(alreadyQueued));
        when(requests.saveAll(anyIterable())).thenAnswer(inv -> {
            saveCalls.incrementAndGet();
            List<UpgradeRequest> batch = new ArrayList<>();
            inv.<Iterable<UpgradeRequest>>getArgument(0).forEach(r -> {
                r.setId(ids.incrementAndGet());
                batch.add(r);
            });
            saved.addAll(batch);
            return batch;
        });
        when(outbox.publish(anyString(), anyString(), anyString(), anyString(), any(), any())).thenAnswer(inv -> {
            correlationIdsAtPublish.add(CorrelationId.current());
            return null;
        });
    }

    private static StagedAccount account(String no, String type, String kyc, String balance) {
        return new StagedAccount(no.hashCode(), 2, no, "C-" + no, "Customer " + no, no + "@example.com", type,
            balance == null ? null : new BigDecimal(balance), kyc);
    }

    private static FileLoaded file(long id, int rows) {
        return new FileLoaded(id, "accounts_20270201.xlsx", DAY, rows, rows, 0);
    }

    @Test
    void eligibleAccountsArePendingAndTheRestRejectedWithTheirReasonAndAnEvent() {
        when(staging.accounts(7)).thenReturn(Flux.just(
            account("OK1", "SAVINGS", "VERIFIED", "100"),
            account("OK2", "current", "VERIFIED", "0"),
            account("NOKYC", "SAVINGS", "PENDING", "100"),
            account("NOPATH", "LOAN", "VERIFIED", "100"),
            account("NOBAL", "SAVINGS", "VERIFIED", null)));

        var plan = planner.plan(file(7, 5), "file-7");

        assertThat(plan.pending()).isEqualTo(2);
        assertThat(plan.rejected()).isEqualTo(3);
        assertThat(saved).filteredOn(r -> r.getStatus().equals(UpgradeRequest.PENDING))
            .extracting(UpgradeRequest::getAccountNo, UpgradeRequest::getTargetType)
            .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("OK1", "SAVINGS_PLUS"), org.assertj.core.groups.Tuple.tuple("OK2", "CURRENT_PLUS"));
        assertThat(saved).filteredOn(r -> r.getAccountNo().equals("NOKYC")).singleElement()
            .satisfies(r -> assertThat(r.getReason()).contains("KYC status is PENDING"));
        assertThat(saved).filteredOn(r -> r.getAccountNo().equals("NOPATH")).singleElement()
            .satisfies(r -> assertThat(r.getReason()).contains("no upgrade path"));
        assertThat(saved).filteredOn(r -> r.getAccountNo().equals("NOBAL")).singleElement()
            .satisfies(r -> assertThat(r.getReason()).contains("balance is missing"));
        verify(outbox).publish(eq(Topics.UPGRADE_REJECTED), eq("NOKYC"), eq("UpgradeRejected"), eq("NOKYC"), any(), any());
        verify(outbox, never()).publish(eq(Topics.UPGRADE_REJECTED), eq("OK1"), anyString(), anyString(), any(), any());
    }

    @Test
    void everyRequestAndEventKeepsTheFilesCorrelationId() {
        when(staging.accounts(8)).thenReturn(Flux.just(account("A", "SAVINGS", "VERIFIED", "1"), account("B", "LOAN", "VERIFIED", "1")));

        planner.plan(file(8, 2), "file-8");

        assertThat(saved).allMatch(r -> "file-8".equals(r.getCorrelationId()) && r.getSourceFileId() == 8 && DAY.equals(r.getBusinessDate()));
        assertThat(correlationIdsAtPublish).containsExactly("file-8");
    }

    @Test
    void deliveringTheSameFileAgainQueuesNothingTwice() {
        alreadyQueued.addAll(List.of("A", "B"));
        when(staging.accounts(9)).thenReturn(Flux.just(account("A", "SAVINGS", "VERIFIED", "1"),
            account("B", "SAVINGS", "VERIFIED", "1"), account("C", "SAVINGS", "VERIFIED", "1"), account("C", "SAVINGS", "VERIFIED", "1")));

        var plan = planner.plan(file(9, 4), "file-9");

        assertThat(plan.alreadyQueued()).isEqualTo(3);
        assertThat(saved).extracting(UpgradeRequest::getAccountNo).containsExactly("C");
    }

    @Test
    void largeFilesAreSavedInChunksEachInItsOwnTransaction() {
        var accounts = IntStream.rangeClosed(1, 1_201).mapToObj(i -> account("L" + i, "SAVINGS", "VERIFIED", "5")).toList();
        when(staging.accounts(10)).thenReturn(Flux.fromIterable(accounts));

        var plan = planner.plan(file(10, 1_201), "file-10");

        assertThat(plan.pending()).isEqualTo(1_201);
        assertThat(saveCalls).as("chunks of 500").hasValue(3);
    }
}
