package com.example.starter.flood;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.flood.service.RollingRoutingService;
import com.example.starter.flood.web.dto.InflowPoint;
import com.example.starter.flood.web.dto.RollingRoutingRequest;
import com.example.starter.flood.web.dto.RollingRoutingView;
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
 * 滚动演算真实并发 H2 测试：并发滚动重算同一处置链，
 * (warning_id, version) 唯一约束保证恰一个新版本，失败事务整体回滚
 * （不残留级别提升事件与方案版本）。
 */
class RollingConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RollingRoutingService rollingService;
    @Autowired
    private FlowSupport flow;

    @Test
    @DisplayName("并发滚动重算：恰一个生成新版本，其余冲突失败且整体回滚，库内只有一个新版本与一个生效方案")
    void concurrentRollingOnlyOneVersion() throws Exception {
        WarningView warning = flow.openWarning("w-roll-concurrent");
        RoutingView routing = flow.route("r-roll-concurrent", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("400001"),
                new BigDecimal("150"), new BigDecimal("10"));
        flow.createPlan("p-roll-concurrent", warning.warningNo(),
                routing.routingNo(), FlowSupport.safeBoundaryOpenings());
        clock.advanceMinutes(30);

        List<InflowPoint> hydrograph = List.of(new InflowPoint(0, new BigDecimal("300")),
                new InflowPoint(30, new BigDecimal("300")));
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        java.util.List<Future<?>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    try {
                        RollingRoutingView view = rollingService.roll(new RollingRoutingRequest(
                                "rr-concurrent-" + idx, warning.warningNo(), hydrograph,
                                null, null));
                        if (view.version() == 2) {
                            success.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failed.incrementAndGet();
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
        assertThat(failed.get()).isEqualTo(threads - 1);

        // 恰一个新演算版本（版本 2），失败事务不残留版本
        Integer versionTwoCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM flood_routing WHERE version = 2", Integer.class);
        assertThat(versionTwoCount).isEqualTo(1);
        // 级别提升事件恰一条（同事务回滚不残留）
        Integer escalateEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM warning_event WHERE event_type = 'LEVEL_ESCALATED'",
                Integer.class);
        assertThat(escalateEvents).isEqualTo(1);
        String level = jdbcTemplate.queryForObject(
                "SELECT warning_level FROM warning_chain WHERE warning_no = ?",
                String.class, warning.warningNo());
        assertThat(level).isEqualTo("RED");
        // 方案版本恰两个（v1 SUPERSEDED + v2 EFFECTIVE），生效方案唯一
        Integer planCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM release_plan", Integer.class);
        assertThat(planCount).isEqualTo(2);
        Integer effectiveCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM release_plan WHERE status = 'EFFECTIVE'", Integer.class);
        assertThat(effectiveCount).isEqualTo(1);
        Integer adviceCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM order_conflict_advice", Integer.class);
        assertThat(adviceCount).isEqualTo(1);
    }
}
