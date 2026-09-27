package com.example.starter.pump.business;

import com.example.starter.pump.error.ApiException;
import com.example.starter.pump.service.PumpdownFacade;
import com.example.starter.pump.service.TideFacade;
import com.example.starter.pump.testsupport.AbstractIntegrationTest;
import com.example.starter.pump.web.dto.CandidateConfirmRequest;
import com.example.starter.pump.web.dto.GateReceiptRequest;
import com.example.starter.pump.web.dto.TideControlCreateRequest;
import com.example.starter.pump.web.dto.TideControlUpdateRequest;
import com.example.starter.pump.web.dto.TideIngestRequest;
import com.example.starter.pump.web.dto.WarningRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 潮汐并发边界（真实多线程 + H2 约束）：并发确认唯一受理、并发潮位去重、
 * 并发版本变更按一致提交顺序且失配方拿到新版本。
 */
class TideConcurrencyTest extends AbstractIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 8, 0);

    @Autowired
    private TideFacade tideFacade;

    @Autowired
    private PumpdownFacade pumpdownFacade;

    private Long setupCandidateOnly() {
        tideFacade.createControl(new TideControlCreateRequest("tc-1", "ZN", new BigDecimal("4.500")));
        pumpdownFacade.handleWarning(new WarningRequest("w-1", "ZN", "ORANGE", T0));
        return tideFacade.ingestTide(new TideIngestRequest(
                "tr-enter", "ZN", OffsetDateTime.parse("2026-09-26T00:05:00Z"),
                new BigDecimal("4.500"))).candidateId();
    }

    private Long setupCandidateWithReceipts() {
        Long candidateId = setupCandidateOnly();
        tideFacade.submitReceipt(candidateId, new GateReceiptRequest(
                "gr-1", "PS-01", new BigDecimal("80.00")));
        tideFacade.submitReceipt(candidateId, new GateReceiptRequest(
                "gr-2", "PS-02", new BigDecimal("75.00")));
        return candidateId;
    }

    @Test
    void concurrentConfirms_onlyOneSwitches_otherGetsVersionConflict() throws Exception {
        Long candidateId = setupCandidateWithReceipts();

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Future<?>[] futures = new Future[threads];
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures[i] = pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        tideFacade.confirm(candidateId, new CandidateConfirmRequest(
                                "cf-" + idx, 1, T0.plusMinutes(30)));
                        accepted.incrementAndGet();
                    } catch (ApiException ex) {
                        // 版本失配或同时刻任务冲突均属唯一受理的拒绝表现
                        if ("VERSION_CONFLICT".equals(ex.getCode())
                                || "STATION_TIME_CONFLICT".equals(ex.getCode())
                                || "CANDIDATE_STATE_CONFLICT".equals(ex.getCode())
                                || "CANDIDATE_NOT_PENDING".equals(ex.getCode())) {
                            rejected.incrementAndGet();
                        } else {
                            throw new RuntimeException(ex);
                        }
                    }
                    return null;
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> f : futures) {
                f.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(accepted.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
        // 唯一切换：仅一套新序列、两站各一条启泵任务、版本只加一次
        assertThat(count("pump_task")).isEqualTo(2);
        assertThat(count("mode_trajectory")).isEqualTo(1);
        assertThat(tideFacade.trajectory("ZN").currentVersion()).isEqualTo(2);
        assertThat(tideFacade.candidateDetail(candidateId).status()).isEqualTo("CONFIRMED");
    }

    @Test
    void concurrentTideIngest_sameZoneSameInstant_deduplicatesToOne() throws Exception {
        tideFacade.createControl(new TideControlCreateRequest("tc-1", "ZN", new BigDecimal("4.500")));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger deduplicated = new AtomicInteger();
        Future<?>[] futures = new Future[threads];
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures[i] = pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    var result = tideFacade.ingestTide(new TideIngestRequest(
                            "tr-" + idx, "ZN", OffsetDateTime.parse("2026-09-26T00:00:00Z"),
                            new BigDecimal("4.100")));
                    if (result.deduplicated()) {
                        deduplicated.incrementAndGet();
                    } else {
                        accepted.incrementAndGet();
                    }
                    return null;
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> f : futures) {
                f.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(accepted.get()).isEqualTo(1);
        assertThat(deduplicated.get()).isEqualTo(threads - 1);
        assertThat(count("tide_reading")).isEqualTo(1);
    }

    @Test
    void concurrentConfirmAndThresholdUpdate_consistentCommitOrder_loserSeesNewVersion()
            throws Exception {
        Long candidateId = setupCandidateWithReceipts();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger confirmOk = new AtomicInteger();
        AtomicInteger updateOk = new AtomicInteger();
        AtomicInteger conflict409 = new AtomicInteger();
        Future<?> confirmFuture = pool.submit(() -> {
            ready.countDown();
            start.await();
            try {
                tideFacade.confirm(candidateId, new CandidateConfirmRequest(
                        "cf-1", 1, T0.plusMinutes(30)));
                confirmOk.incrementAndGet();
            } catch (ApiException ex) {
                if ("VERSION_CONFLICT".equals(ex.getCode())) {
                    conflict409.incrementAndGet();
                } else {
                    throw new RuntimeException(ex);
                }
            }
            return null;
        });
        Future<?> updateFuture = pool.submit(() -> {
            ready.countDown();
            start.await();
            try {
                tideFacade.updateThreshold("ZN",
                        new TideControlUpdateRequest("tc-u", new BigDecimal("4.600"), 1));
                updateOk.incrementAndGet();
            } catch (ApiException ex) {
                if ("VERSION_CONFLICT".equals(ex.getCode())) {
                    conflict409.incrementAndGet();
                } else {
                    throw new RuntimeException(ex);
                }
            }
            return null;
        });
        try {
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            confirmFuture.get(15, TimeUnit.SECONDS);
            updateFuture.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // 一致提交顺序：恰好一个成功，失配者拿到 409 与新版本；版本只加一次
        assertThat(confirmOk.get() + updateOk.get()).isEqualTo(1);
        assertThat(conflict409.get()).isEqualTo(1);
        assertThat(tideFacade.trajectory("ZN").currentVersion()).isEqualTo(2);
    }

    @Test
    void concurrentReceipts_sameStation_sameCandidate_onlyOneAccepted() throws Exception {
        Long candidateId = setupCandidateOnly();
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        Future<?>[] futures = new Future[threads];
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures[i] = pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        tideFacade.submitReceipt(candidateId, new GateReceiptRequest(
                                "gr-dup-" + idx, "PS-01", new BigDecimal("80.00")));
                        accepted.incrementAndGet();
                    } catch (ApiException ex) {
                        if ("GATE_RECEIPT_DUPLICATED".equals(ex.getCode())) {
                            conflict.incrementAndGet();
                        } else {
                            throw new RuntimeException(ex);
                        }
                    }
                    return null;
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> f : futures) {
                f.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(accepted.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
        // 同候选同站回执唯一：并发中仅一条落账
        assertThat(count("switch_gate_receipt")).isEqualTo(1);
    }
}
