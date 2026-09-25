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
import com.example.starter.domain.OrderStatus;
import com.example.starter.dto.OrderReceiveRequest;
import com.example.starter.dto.OrderReceiveResponse;
import com.example.starter.web.ApiConflictException;

/**
 * H2 真实并发测试：行锁串行化同风场同时刻接收，唯一受理；新指令原子作废旧指令。
 */
@SpringBootTest
class DispatchConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DispatchService dispatchService;

    @BeforeEach
    void setUp() {
        resetDb();
        pinClock(10, 0, 0);
    }

    @Test
    @DisplayName("同一 requestId 并发接收：唯一受理，其余全部返回首次结果")
    void sameRequestIdConcurrentAcceptedOnce() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OrderReceiveResponse>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return dispatchService.receive(new OrderReceiveRequest("same-key", FARM, 5000));
            }));
        }
        start.countDown();

        List<OrderReceiveResponse> results = new ArrayList<>();
        for (Future<OrderReceiveResponse> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        long distinctOrderIds = results.stream().map(OrderReceiveResponse::orderId).distinct().count();
        assertThat(distinctOrderIds).isEqualTo(1);
        long firstCount = results.stream().filter(r -> !r.replayed()).count();
        long replayCount = results.stream().filter(OrderReceiveResponse::replayed).count();
        assertThat(firstCount).isEqualTo(1);
        assertThat(replayCount).isEqualTo(threads - 1);

        Integer orderCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM curtail_order", Integer.class);
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_record WHERE request_id = 'same-key'", Integer.class);
        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM curtail_order WHERE active_flag = 'Y'", Integer.class);
        assertThat(orderCount).isEqualTo(1);
        assertThat(idemCount).isEqualTo(1);
        assertThat(activeCount).isEqualTo(1);
    }

    @Test
    @DisplayName("同风场同时刻不同 requestId 并发接收：仅一个成功，其余 409，无双执行中指令")
    void sameFarmSameTimeConcurrentOnlyOneAccepted() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                return dispatchService.receive(
                        new OrderReceiveRequest("key-" + idx, FARM, 5000));
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
                // java.util.concurrent.ExecutionException 包装 ApiConflictException
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

        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM curtail_order WHERE active_flag = 'Y'", Integer.class);
        Integer orderCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM curtail_order", Integer.class);
        assertThat(activeCount).isEqualTo(1);
        assertThat(orderCount).isEqualTo(1);
    }

    @Test
    @DisplayName("新指令到达（不同时刻）原子切换：旧指令作废，全场恰一个执行中指令")
    void newOrderAtomicallySupersedesOldOrder() {
        pinClock(10, 0, 0);
        OrderReceiveResponse first = dispatchService.receive(
                new OrderReceiveRequest("old-key", FARM, 5000));

        // 5 分钟后新指令到达（非同时刻）：原子切换。
        pinClock(10, 5, 0);
        OrderReceiveResponse second = dispatchService.receive(
                new OrderReceiveRequest("new-key", FARM, 4500));

        assertThat(second.orderId()).isNotEqualTo(first.orderId());

        var oldOrder = jdbc.queryForMap(
                "SELECT status, active_flag, superseded_by_order_id FROM curtail_order WHERE id = ?",
                first.orderId());
        var newOrder = jdbc.queryForMap(
                "SELECT status, active_flag, superseded_by_order_id FROM curtail_order WHERE id = ?",
                second.orderId());

        assertThat((String) oldOrder.get("STATUS")).isEqualTo(OrderStatus.SUPERSEDED.name());
        assertThat(oldOrder.get("ACTIVE_FLAG")).isNull();
        assertThat(((Number) oldOrder.get("SUPERSEDED_BY_ORDER_ID")).longValue())
                .isEqualTo(second.orderId());
        assertThat((String) newOrder.get("STATUS")).isEqualTo(OrderStatus.DISPATCHED.name());
        assertThat((String) newOrder.get("ACTIVE_FLAG")).isEqualTo("Y");

        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM curtail_order WHERE active_flag = 'Y'", Integer.class);
        assertThat(activeCount).isEqualTo(1);
    }

    @Test
    @DisplayName("恰满 10 分钟多机组并发回报：告警与重分摊恰好一次，回报全部受理")
    void concurrentReportsAtDueTimeFireAlertOnce() throws Exception {
        OrderReceiveResponse order = dispatchService.receive(
                new OrderReceiveRequest("conc-alert", FARM, 5000));
        long orderId = order.orderId();
        // 恰满 10 分钟。
        pinClock(10, 10, 0);

        String[] turbines = {"T-01", "T-02", "T-03"};
        int[] powers = {1900, 1500, 2300};
        ExecutorService pool = Executors.newFixedThreadPool(turbines.length);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < turbines.length; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                return dispatchService.report(new com.example.starter.dto.ReportRequest(
                        "rp-conc-" + idx, turbines[idx], localInstant(10, 10, 0), powers[idx]));
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        Integer reports = jdbc.queryForObject(
                "SELECT COUNT(*) FROM execution_report WHERE order_id = ?", Integer.class, orderId);
        Integer alerts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM alert_event WHERE order_id = ?", Integer.class, orderId);
        Integer cycles = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dispatch_cycle WHERE order_id = ?", Integer.class, orderId);
        assertThat(reports).isEqualTo(3);
        assertThat(alerts).isEqualTo(1);
        assertThat(cycles).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT current_cycle_no FROM curtail_order WHERE id = ?",
                Integer.class, orderId)).isEqualTo(2);
    }

    @Test
    @DisplayName("被作废指令回报记为迟到事件；新指令仍为执行中")
    void reportToSupersededOrderRecordedAsLate() {
        pinClock(10, 0, 0);
        OrderReceiveResponse old = dispatchService.receive(
                new OrderReceiveRequest("old-key-2", FARM, 5000));
        pinClock(10, 5, 0);
        OrderReceiveResponse latest = dispatchService.receive(
                new OrderReceiveRequest("new-key-2", FARM, 4500));

        // 回报归属执行中指令（新），旧指令已是 SUPERSEDED；对旧指令的直接复算可查其状态。
        var detail = dispatchService.report(new com.example.starter.dto.ReportRequest(
                "rp-new", "T-01", localInstant(10, 6, 0), 1700));
        assertThat(detail.late()).isFalse();
        assertThat(detail.orderNo()).isEqualTo(latest.orderNo());

        var oldDetail = dispatchService.detail(old.orderNo());
        assertThat(oldDetail.status()).isEqualTo("SUPERSEDED");
        var newDetail = dispatchService.detail(latest.orderNo());
        assertThat(newDetail.status()).isEqualTo("DISPATCHED");
        assertThat(newDetail.executionCurve()).hasSize(1);
    }
}
