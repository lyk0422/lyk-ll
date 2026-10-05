package com.example.starter.flood;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.flood.exception.FloodControlException;
import com.example.starter.flood.service.PlanService;
import com.example.starter.flood.service.RollingService;
import com.example.starter.flood.web.dto.PlanRequest;
import com.example.starter.flood.web.dto.PlanView;
import com.example.starter.flood.web.dto.RollingRequest;
import com.example.starter.flood.web.dto.RollingVersionView;
import com.example.starter.flood.web.dto.WarningView;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 滚动演算真实并发 H2 测试：并发滚动版本唯一约束、同键并发幂等、
 * 滚动追加方案与手工方案并发时执行中方案唯一。
 */
class RollingConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RollingService rollingService;
    @Autowired
    private PlanService planService;
    @Autowired
    private FlowSupport flow;

    @Test
    @DisplayName("并发滚动（异键同时刻）：恰一个版本落库，其余 409 版本冲突")
    void concurrentRollingOnlyOneVersion() throws Exception {
        WarningView warning = flow.openWarning("w-roll-conc");
        flow.submitHydrograph("hg-conc", warning.warningNo(),
                new BigDecimal("5"), new BigDecimal("8"));
        flow.route("r-conc", warning.warningNo(), new BigDecimal("200.0000"),
                new BigDecimal("400001"), new BigDecimal("150"), new BigDecimal("10"));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    try {
                        rollingService.roll(warning.warningNo(), new RollingRequest(
                                "gd-conc-" + idx, new BigDecimal("200.0000"),
                                new BigDecimal("150"), new BigDecimal("10")));
                        success.incrementAndGet();
                    } catch (FloodControlException e) {
                        if ("IDEMPOTENT_CONFLICT".equals(e.getCode())
                                && e.getMessage().contains("并发滚动演算版本冲突")) {
                            conflict.incrementAndGet();
                        } else {
                            throw e;
                        }
                    }
                    return null;
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM rolling_routing", Integer.class);
        assertThat(versionCount).isEqualTo(1);
        Integer distinctVersion = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT version_no) FROM rolling_routing", Integer.class);
        assertThat(distinctVersion).isEqualTo(1);
    }

    @Test
    @DisplayName("并发滚动（同键同参）：幂等收敛，全部返回同一版本，库内仅一行")
    void concurrentRollingSameKeyIdempotent() throws Exception {
        WarningView warning = flow.openWarning("w-roll-conc-idem");
        flow.submitHydrograph("hg-conc-idem", warning.warningNo(),
                new BigDecimal("5"), new BigDecimal("8"));

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<String> rollingNos = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger conflicts = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    try {
                        RollingVersionView view = rollingService.roll(warning.warningNo(),
                                new RollingRequest("gd-conc-idem", new BigDecimal("200.0000"),
                                        new BigDecimal("150"), new BigDecimal("10")));
                        rollingNos.add(view.rollingNo());
                    } catch (FloodControlException e) {
                        // 首请求事务未提交时的进行中标记：视为并发冲突，稍后重试可收敛
                        if ("IDEMPOTENT_CONFLICT".equals(e.getCode())) {
                            conflicts.incrementAndGet();
                        } else {
                            throw e;
                        }
                    }
                    return null;
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(rollingNos).isNotEmpty();
        assertThat(rollingNos).allMatch(no -> no.equals(rollingNos.get(0)));
        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM rolling_routing", Integer.class);
        assertThat(versionCount).isEqualTo(1);
    }

    @Test
    @DisplayName("滚动追加方案与手工方案并发：唯一索引兜底，全库执行中方案恰一个")
    void concurrentAutoAppendAndManualPlanSingleEffective() throws Exception {
        // 处置链 A：过程线峰值 25，无方案 → 滚动必触发自动追加
        WarningView warning = flow.openWarning("w-roll-conc-plan");
        flow.submitHydrograph("hg-conc-plan", warning.warningNo(),
                new BigDecimal("10"), new BigDecimal("25"));
        var routing = flow.route("r-conc-plan", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("400001"),
                new BigDecimal("150"), new BigDecimal("10"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger manualOk = new AtomicInteger();
        AtomicInteger rollingOk = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                try {
                    planService.create(new PlanRequest("p-conc-manual", warning.warningNo(),
                            routing.routingNo(), FlowSupport.safeBoundaryOpenings()));
                    manualOk.incrementAndGet();
                } catch (FloodControlException e) {
                    if (!"PLAN_ALREADY_EFFECTIVE".equals(e.getCode())) {
                        throw e;
                    }
                }
                return null;
            }));
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                try {
                    rollingService.roll(warning.warningNo(), new RollingRequest(
                            "gd-conc-plan", new BigDecimal("200.0000"),
                            new BigDecimal("150"), new BigDecimal("10")));
                    rollingOk.incrementAndGet();
                } catch (FloodControlException e) {
                    if (!"PLAN_ALREADY_EFFECTIVE".equals(e.getCode())) {
                        throw e;
                    }
                }
                return null;
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 至少一方成功；无论时序如何，执行中方案恰一个
        assertThat(manualOk.get() + rollingOk.get()).isGreaterThanOrEqualTo(1);
        Integer effectiveCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM release_plan WHERE status = 'EFFECTIVE'", Integer.class);
        assertThat(effectiveCount).isEqualTo(1);
        Integer activeKeys = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT active_key) FROM release_plan "
                        + "WHERE active_key IS NOT NULL", Integer.class);
        assertThat(activeKeys).isEqualTo(1);
    }
}
