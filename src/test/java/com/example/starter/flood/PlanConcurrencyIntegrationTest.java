package com.example.starter.flood;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.flood.exception.FloodControlException;
import com.example.starter.flood.service.PlanService;
import com.example.starter.flood.web.dto.OpeningItem;
import com.example.starter.flood.web.dto.PlanRequest;
import com.example.starter.flood.web.dto.PlanView;
import com.example.starter.flood.web.dto.RoutingView;
import com.example.starter.flood.web.dto.WarningView;
import java.math.BigDecimal;
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
 * 真实并发 H2 测试：险情处置链下并发制定泄流方案，
 * 唯一索引保证同一尾矿库同时刻只有一个生效方案。
 */
class PlanConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PlanService planService;
    @Autowired
    private FlowSupport flow;

    @Test
    @DisplayName("并发制定两个方案：恰一个生效，另一个被唯一约束拒绝，库内只有一个执行中方案")
    void concurrentPlansOnlyOneEffective() throws Exception {
        WarningView warning = flow.openWarning("w-concurrent-plan");
        RoutingView routing = flow.route("r-concurrent-plan", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("400001"),
                new BigDecimal("150"), new BigDecimal("10"));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        java.util.List<Future<?>> futures = new java.util.ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    List<OpeningItem> openings = List.of(new OpeningItem(
                            "并发孔口-" + idx, new BigDecimal("1.0"),
                            new BigDecimal("10.000")));
                    try {
                        PlanView view = planService.create(new PlanRequest(
                                "p-concurrent-" + idx, warning.warningNo(),
                                routing.routingNo(), openings));
                        if ("EFFECTIVE".equals(view.status())) {
                            success.incrementAndGet();
                        }
                    } catch (FloodControlException e) {
                        if ("PLAN_ALREADY_EFFECTIVE".equals(e.getCode())) {
                            rejected.incrementAndGet();
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
        assertThat(rejected.get()).isEqualTo(threads - 1);

        Integer effectiveCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM release_plan WHERE status = 'EFFECTIVE'", Integer.class);
        Integer totalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM release_plan", Integer.class);
        Integer distinctActiveKey = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT active_key) FROM release_plan WHERE active_key IS NOT NULL",
                Integer.class);
        assertThat(effectiveCount).isEqualTo(1);
        assertThat(totalCount).isEqualTo(1);
        assertThat(distinctActiveKey).isEqualTo(1);
    }
}
