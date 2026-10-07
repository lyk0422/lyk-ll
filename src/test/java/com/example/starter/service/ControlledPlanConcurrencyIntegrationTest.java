package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.dto.ControlledOrderReceiveRequest;
import com.example.starter.dto.ControlledOrderReceiveResponse;
import com.example.starter.dto.ControlledTurbineInput;
import com.example.starter.dto.MinuteConfirmRequest;
import com.example.starter.dto.MinuteConfirmResponse;
import com.example.starter.dto.MinuteReportItem;
import com.example.starter.web.ApiConflictException;

/**
 * 受控分段模式 H2 真实并发测试：机组竞争（同场并发登记仅一个受理）、
 * 同键并发登记唯一受理其余重放、同一分钟并发确认仅一个生效。
 */
@SpringBootTest
class ControlledPlanConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ControlledPlanService controlledPlanService;

    @BeforeEach
    void setUp() {
        resetDb();
        pinClock(10, 0, 0);
    }

    private ControlledOrderReceiveRequest request(String requestId) {
        return new ControlledOrderReceiveRequest(requestId, FARM, 2953, null, null, null,
                List.of(
                        new ControlledTurbineInput("T-01", 2000, 500, 3),
                        new ControlledTurbineInput("T-02", 1000, 100, 2),
                        new ControlledTurbineInput("T-03", 2400, 500, 3)));
    }

    @Test
    @DisplayName("机组竞争：不同 requestId 同时刻并发登记，恰好一个受理，其余 409，占用唯一")
    void concurrentRegistrationOnlyOneSucceeds() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    controlledPlanService.register(request("race-" + idx));
                    success.incrementAndGet();
                } catch (ApiConflictException ex) {
                    conflict.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
        // 每台机组同一时刻只有一份执行中分段计划。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM curtail_order", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM curtail_order WHERE active_flag = 'Y'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM controlled_plan WHERE status = 'EXECUTING'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM controlled_occupation", Integer.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("同一 requestId 并发登记：唯一受理，其余全部重放首次冻结结果")
    void sameRequestIdConcurrentRegisterReplays() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ControlledOrderReceiveResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return controlledPlanService.register(request("same-key"));
            }));
        }
        start.countDown();
        List<ControlledOrderReceiveResponse> results = new ArrayList<>();
        for (Future<ControlledOrderReceiveResponse> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(results.stream().map(ControlledOrderReceiveResponse::orderId).distinct().count())
                .isEqualTo(1);
        assertThat(results.stream().filter(r -> !r.replayed()).count()).isEqualTo(1);
        assertThat(results.stream().filter(ControlledOrderReceiveResponse::replayed).count())
                .isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM curtail_order", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM controlled_occupation", Integer.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("同一分钟并发确认：恰好一个生效，其余 409，进度与功率只推进一次")
    void concurrentConfirmSameMinuteOnlyOneSucceeds() throws Exception {
        ControlledOrderReceiveResponse resp = controlledPlanService.register(request("c-conc"));
        pinClock(10, 1, 0);
        List<MinuteReportItem> reports = List.of(
                new MinuteReportItem("T-01", 1500),
                new MinuteReportItem("T-02", 900),
                new MinuteReportItem("T-03", 1900));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    MinuteConfirmResponse r = controlledPlanService.confirmMinute(
                            resp.orderNo(), 1, new MinuteConfirmRequest("cf-race-" + idx, reports));
                    if (r.executed()) {
                        success.incrementAndGet();
                    }
                } catch (ApiConflictException ex) {
                    conflict.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject(
                "SELECT confirmed_minute FROM controlled_plan", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM controlled_minute_confirm", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT available_power_kw FROM wind_turbine WHERE turbine_code = 'T-02'",
                Integer.class)).isEqualTo(900);
    }
}
