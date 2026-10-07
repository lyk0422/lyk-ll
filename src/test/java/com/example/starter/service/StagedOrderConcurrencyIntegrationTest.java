package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.dto.StagedOrderReceiveRequest;
import com.example.starter.dto.StagedOrderReceiveResponse;
import com.example.starter.dto.StagedReceiptItem;
import com.example.starter.dto.StagedReceiptRequest;
import com.example.starter.dto.StagedReceiptResponse;
import com.example.starter.dto.StagedTurbineInput;
import com.example.starter.web.ApiConflictException;

/**
 * H2 真实并发测试：分段指令并发登记机组竞争唯一受理、
 * 同分钟并发回执唯一确认、同 requestId 并发回执重放一致。
 */
@SpringBootTest
class StagedOrderConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DispatchService dispatchService;

    @Autowired
    private StagedPlanService stagedPlanService;

    @BeforeEach
    void setUp() {
        resetDb();
        pinClock(10, 0, 0);
    }

    private void seedAvailable1000() {
        jdbc.update("UPDATE wind_turbine SET available_power_kw = 1000 WHERE turbine_code = 'T-01'");
        jdbc.update("UPDATE wind_turbine SET available_power_kw = 300 WHERE turbine_code = 'T-02'");
        jdbc.update("UPDATE wind_turbine SET available_power_kw = 450 WHERE turbine_code = 'T-03'");
    }

    private StagedOrderReceiveRequest scenarioRequest(String requestId) {
        return new StagedOrderReceiveRequest(requestId, FARM, 1550, localInstant(10, 0, 0),
                null, null, null,
                List.of(new StagedTurbineInput("T-01", 1000, 100, 2)));
    }

    @Test
    @DisplayName("机组竞争：同风场同时刻并发登记分段指令，仅一单受理，机组占用唯一")
    void concurrentRegistrationOnlyOneOccupiesTurbine() throws Exception {
        seedAvailable1000();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                return dispatchService.receiveStagedOrder(scenarioRequest("so-conc-" + idx));
            }));
        }
        start.countDown();

        int success = 0;
        int conflict = 0;
        for (Future<?> f : futures) {
            try {
                f.get(30, TimeUnit.SECONDS);
                success++;
            } catch (Exception ex) {
                boolean isConflict = ex.getCause() instanceof ApiConflictException
                        || (ex.getCause() != null
                            && ex.getCause().getCause() instanceof ApiConflictException);
                assertThat(isConflict).isTrue();
                conflict++;
            }
        }
        pool.shutdown();

        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        // 全场恰一个执行中指令、一份分段计划、一条机组占用。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM curtail_order WHERE active_flag = 'Y'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staged_plan", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM staged_plan_turbine WHERE occupied_flag = 'Y'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("同分钟并发回执（不同 requestId）：恰好一个确认成功，其余 409，进度只推进一次")
    void concurrentReceiptSameMinuteConfirmedOnce() throws Exception {
        seedAvailable1000();
        StagedOrderReceiveResponse order =
                dispatchService.receiveStagedOrder(scenarioRequest("so-conc-r"));
        pinClock(10, 1, 0);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                return stagedPlanService.confirmMinute(new StagedReceiptRequest(
                        "sr-conc-" + idx, order.orderNo(), 1,
                        List.of(new StagedReceiptItem("T-01", 900))));
            }));
        }
        start.countDown();

        int success = 0;
        int conflict = 0;
        for (Future<?> f : futures) {
            try {
                f.get(30, TimeUnit.SECONDS);
                success++;
            } catch (Exception ex) {
                boolean isConflict = ex.getCause() instanceof ApiConflictException
                        || (ex.getCause() != null
                            && ex.getCause().getCause() instanceof ApiConflictException);
                assertThat(isConflict).isTrue();
                conflict++;
            }
        }
        pool.shutdown();

        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        // 进度只推进一次：当前分钟 1、确认记录 1 条、实际功率恰为阶梯值 900。
        assertThat(jdbc.queryForObject(
                "SELECT current_minute FROM staged_plan WHERE id = ?",
                Integer.class, order.planId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM staged_minute_confirm WHERE plan_id = ?",
                Integer.class, order.planId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT available_power_kw FROM wind_turbine WHERE turbine_code = 'T-01'",
                Integer.class)).isEqualTo(900);
    }

    @Test
    @DisplayName("同 requestId 并发回执：唯一确认，其余全部重放首次结果")
    void sameRequestIdConcurrentReceiptReplayed() throws Exception {
        seedAvailable1000();
        StagedOrderReceiveResponse order =
                dispatchService.receiveStagedOrder(scenarioRequest("so-conc-idem"));
        pinClock(10, 1, 0);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<StagedReceiptResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return stagedPlanService.confirmMinute(new StagedReceiptRequest(
                        "sr-same", order.orderNo(), 1,
                        List.of(new StagedReceiptItem("T-01", 900))));
            }));
        }
        start.countDown();

        List<StagedReceiptResponse> results = new ArrayList<>();
        for (Future<StagedReceiptResponse> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        long firstCount = results.stream().filter(r -> !r.replayed()).count();
        long replayCount = results.stream().filter(StagedReceiptResponse::replayed).count();
        assertThat(firstCount).isEqualTo(1);
        assertThat(replayCount).isEqualTo(threads - 1);
        assertThat(results).allSatisfy(r -> {
            assertThat(r.executed()).isTrue();
            assertThat(r.currentMinute()).isEqualTo(1);
        });
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM staged_minute_confirm WHERE plan_id = ?",
                Integer.class, order.planId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_record WHERE request_id = 'sr-same'",
                Integer.class)).isEqualTo(1);
    }
}
