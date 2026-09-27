package com.example.starter.pump.business;

import com.example.starter.pump.error.ApiException;
import com.example.starter.pump.service.PumpdownFacade;
import com.example.starter.pump.testsupport.AbstractIntegrationTest;
import com.example.starter.pump.web.dto.CandidateConfirmRequest;
import com.example.starter.pump.web.dto.GateReceiptRequest;
import com.example.starter.pump.web.dto.TideConfigRequest;
import com.example.starter.pump.web.dto.TideReadingRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 强排联锁切换并发边界（真实多线程 + H2 行锁/条件更新）：同一候选并发确认只有一个原子生效，
 * 其余零切换；绝不出现一半站先启机。配置版本失配的确认被拒绝并返回当前版本。
 */
class TideStrongDrainConcurrencyTest extends AbstractIntegrationTest {

    private static final OffsetDateTime T1 = OffsetDateTime.of(2026, 9, 26, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private PumpdownFacade facade;

    @Test
    void concurrentConfirmOfSameCandidate_onlyOneAtomicallySwitches() throws Exception {
        facade.handleTideConfig(new TideConfigRequest("cfg", "ZN", new BigDecimal("3.000"), 0));
        Long candidate = facade.handleTideReading(
                new TideReadingRequest("tr", "ZN", T1, new BigDecimal("3.000"))).candidateId();
        facade.handleGateReceipt(new GateReceiptRequest("g1", candidate, "PS-01",
                new BigDecimal("80.00"), T1.plusMinutes(1)));
        facade.handleGateReceipt(new GateReceiptRequest("g2", candidate, "PS-02",
                new BigDecimal("75.00"), T1.plusMinutes(1)));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Future<?>[] futures = new Future[threads];

        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures[i] = pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        facade.handleCandidateConfirm(new CandidateConfirmRequest(
                                "cf-" + idx, candidate, 1, T1.plusMinutes(2)));
                        succeeded.incrementAndGet();
                    } catch (ApiException ex) {
                        // 候选已被首个事务生效/处理：零切换拒绝
                        if ("CANDIDATE_NOT_OPEN".equals(ex.getCode())
                                || "REGIME_VERSION_MISMATCH".equals(ex.getCode())) {
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
                f.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
        // 原子切换：正好两站 START，序列仅一个 ACTIVE
        assertThat(count("strong_drain_task")).isEqualTo(2);
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(1) FROM strong_drain_sequence WHERE state = 'ACTIVE'",
                Integer.class);
        assertThat(active).isEqualTo(1);
    }

    @Test
    void confirmWithStaleRegimeVersion_isRejected_andReportsCurrentVersion() throws Exception {
        facade.handleTideConfig(new TideConfigRequest("cfg", "ZN", new BigDecimal("3.000"), 0));
        Long candidate = facade.handleTideReading(
                new TideReadingRequest("tr", "ZN", T1, new BigDecimal("3.000"))).candidateId();
        facade.handleGateReceipt(new GateReceiptRequest("g1", candidate, "PS-01",
                new BigDecimal("80.00"), T1.plusMinutes(1)));
        facade.handleGateReceipt(new GateReceiptRequest("g2", candidate, "PS-02",
                new BigDecimal("75.00"), T1.plusMinutes(1)));

        // 并发地先把配置版本推进到2（不同requestId）
        facade.handleTideConfig(new TideConfigRequest("cfg2", "ZN", new BigDecimal("3.200"), 1));

        // 旧版本(1)确认被拒绝，返回当前版本2；不发生切换
        try {
            facade.handleCandidateConfirm(new CandidateConfirmRequest(
                    "cf-old", candidate, 1, T1.plusMinutes(2)));
            assertThat(false).as("旧工况版本确认必须被拒绝").isTrue();
        } catch (ApiException.VersionMismatchException ex) {
            assertThat(ex.getCurrentVersion()).isEqualTo(2);
        }
        assertThat(count("strong_drain_task")).isZero();
    }
}
