package com.example.starter.pump.business;

import com.example.starter.pump.error.ApiException;
import com.example.starter.pump.service.PumpdownFacade;
import com.example.starter.pump.testsupport.AbstractIntegrationTest;
import com.example.starter.pump.web.dto.TideConfigRequest;
import com.example.starter.pump.web.dto.TideConfirmRequest;
import com.example.starter.pump.web.dto.TideLevelRequest;
import com.example.starter.pump.web.dto.WarningRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 潮汐并发边界（真实多线程 + H2 行锁/唯一约束）：并发确认同一候选唯一启用；
 * 并发潮位上送只生成一套候选；潮位更新与切换按一致提交顺序推进版本。
 */
class TideConcurrencyTest extends AbstractIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 8, 0);

    @Autowired
    private PumpdownFacade facade;

    private Long setupCandidate() {
        facade.handleTideConfig(new TideConfigRequest("tc-x", "ZN", new BigDecimal("5.000"), null));
        facade.handleWarning(new WarningRequest("w-x", "ZN", "ORANGE", T0));
        return facade.handleTideLevel(new TideLevelRequest("tl-x", "ZN",
                new BigDecimal("5.000"), Instant.parse("2026-09-26T00:01:00Z"), 1)).candidateId();
    }

    private TideConfirmRequest confirmRequest(String requestId, Long candidateId) {
        return new TideConfirmRequest(requestId, candidateId, 1, T0.plusMinutes(30), List.of(
                new TideConfirmRequest.GateReceipt("PS-01", new BigDecimal("80.00")),
                new TideConfirmRequest.GateReceipt("PS-02", new BigDecimal("75.00"))));
    }

    @Test
    void concurrentConfirms_onlyOneActivates() throws Exception {
        Long candidateId = setupCandidate();

        int threads = 8;
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
                        facade.handleTideConfirm(confirmRequest("cf-x" + idx, candidateId));
                        accepted.incrementAndGet();
                    } catch (ApiException ex) {
                        // 配置行锁串行后：版本失配或候选已启用，均属唯一受理
                        if ("VERSION_CONFLICT".equals(ex.getCode())
                                || "CANDIDATE_NOT_PENDING".equals(ex.getCode())
                                || "ZONE_SEQUENCE_CONFLICT".equals(ex.getCode())
                                || "STATION_TIME_CONFLICT".equals(ex.getCode())) {
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
        // 唯一启用：一套序列、两站任务、两条闸门日志、版本只推进一次
        assertThat(count("pump_sequence")).isEqualTo(1);
        assertThat(count("pump_task")).isEqualTo(2);
        assertThat(count("gate_opening_log")).isEqualTo(2);
        assertThat(count("tide_mode_trail")).isEqualTo(1);
        assertThat(facade.tideConfig("ZN").version()).isEqualTo(2);
        assertThat(facade.tideConfig("ZN").currentMode()).isEqualTo("FORCED");
    }

    @Test
    void concurrentTideIngests_generateSingleCandidate() throws Exception {
        facade.handleTideConfig(new TideConfigRequest("tc-y", "ZN", new BigDecimal("5.000"), null));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger late = new AtomicInteger();
        Future<?>[] futures = new Future[threads];
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures[i] = pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    var result = facade.handleTideLevel(new TideLevelRequest("tl-y" + idx, "ZN",
                            new BigDecimal("5.100"),
                            Instant.parse("2026-09-26T00:0" + (idx + 1) + ":00Z"), 1));
                    // 并发到达顺序不等于观测时刻顺序：晚于更大观测时刻提交的较旧潮位只留证据
                    if (result.accepted()) {
                        accepted.incrementAndGet();
                    } else if (result.late()) {
                        late.incrementAndGet();
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

        // 每条上送要么受理要么留迟到证据，总量守恒
        assertThat(accepted.get() + late.get()).isEqualTo(threads);
        assertThat(count("tide_level_reading")).isEqualTo(accepted.get());
        assertThat(count("late_tide_event")).isEqualTo(late.get());
        assertThat(accepted.get()).isGreaterThanOrEqualTo(1);
        // 并发同向判定只生成一套候选，其余复用
        assertThat(count("tide_switch_candidate")).isEqualTo(1);
        assertThat(count("tide_switch_candidate_station")).isEqualTo(2);
    }

    @Test
    void concurrentConfirmAndConfigUpdate_consistentCommitOrder() throws Exception {
        Long candidateId = setupCandidate();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger confirmOk = new AtomicInteger();
        AtomicInteger updateOk = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            Future<?> confirmFuture = pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    facade.handleTideConfirm(confirmRequest("cf-z1", candidateId));
                    confirmOk.incrementAndGet();
                } catch (ApiException ex) {
                    if ("VERSION_CONFLICT".equals(ex.getCode())) {
                        conflicts.incrementAndGet();
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
                    facade.handleTideConfig(new TideConfigRequest("tc-z2", "ZN",
                            new BigDecimal("6.000"), 1));
                    updateOk.incrementAndGet();
                } catch (ApiException ex) {
                    if ("VERSION_CONFLICT".equals(ex.getCode())) {
                        conflicts.incrementAndGet();
                    } else {
                        throw new RuntimeException(ex);
                    }
                }
                return null;
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            confirmFuture.get(15, TimeUnit.SECONDS);
            updateFuture.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // 同一版本只有一次成功变更：另一并发操作按一致提交顺序看到新版本并被拒绝
        assertThat(confirmOk.get() + updateOk.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        assertThat(facade.tideConfig("ZN").version()).isEqualTo(2);
    }
}
