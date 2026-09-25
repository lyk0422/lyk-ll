package com.example.starter.service;

import com.example.starter.testsupport.AbstractIntegrationTest;
import com.example.starter.testsupport.Fixtures;
import com.example.starter.web.ConflictException;
import com.example.starter.web.UnprocessableException;
import com.example.starter.web.dto.AlertRequest;
import com.example.starter.web.dto.ConfirmationRequest;
import com.example.starter.web.dto.DispatchRequest;
import com.example.starter.web.dto.ReadingRequest;
import com.example.starter.web.dto.ReassignmentRequest;
import com.example.starter.web.dto.RestorationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 真实并发边界：同油机并发分配唯一成功、恢复与确认竞态唯一终态、
 * 异常单并发改派只允许一台新油机在途。
 */
class BackupPowerConcurrencyTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private BackupPowerService service;
    @Autowired
    private Fixtures fixtures;

    @Test
    void one_generator_dispatched_concurrently_by_two_stations_only_one_succeeds() throws Exception {
        // 仅一台油机，两个低电量基站同时派单。
        fixtures.generator("GEN-1");
        prepareStationForDispatch("BS-1");
        prepareStationForDispatch("BS-2");

        List<Callable<Object>> tasks = List.of(
                () -> service.handleDispatch(new DispatchRequest("REQ-D1", "BS-1", T0, null)),
                () -> service.handleDispatch(new DispatchRequest("REQ-D2", "BS-2", T0, null))
        );

        List<Future<Object>> futures = runConcurrently(tasks);

        int success = 0;
        int conflict = 0;
        for (Future<Object> future : futures) {
            try {
                future.get(15, TimeUnit.SECONDS);
                success++;
            } catch (Exception e) {
                Throwable cause = e.getCause();
                assertThat(cause).isInstanceOf(ConflictException.class);
                conflict++;
            }
        }
        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(1);
        // 唯一一张活动调度单占用唯一油机。
        assertThat(count("dispatch_order")).isEqualTo(1);
        assertThat(count("generator_active_order")).isEqualTo(1);
        assertThat(count("station_active_order")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM generator WHERE gen_code = 'GEN-1'",
                String.class)).isEqualTo("DISPATCHED");
    }

    @Test
    void same_station_concurrent_dispatch_only_one_order_created() throws Exception {
        fixtures.generator("GEN-1");
        fixtures.generator("GEN-2");
        prepareStationForDispatch("BS-1");

        List<Callable<Object>> tasks = List.of(
                () -> service.handleDispatch(new DispatchRequest("REQ-D1", "BS-1", T0, null)),
                () -> service.handleDispatch(new DispatchRequest("REQ-D2", "BS-1", T0, null))
        );
        List<Future<Object>> futures = runConcurrently(tasks);

        int success = 0;
        for (Future<Object> future : futures) {
            try {
                future.get(15, TimeUnit.SECONDS);
                success++;
            } catch (Exception ignored) {
                // 期望一个 409 STATION_ORDER_ACTIVE。
            }
        }
        assertThat(success).isEqualTo(1);
        assertThat(count("dispatch_order")).isEqualTo(1);
        assertThat(count("station_active_order")).isEqualTo(1);
    }

    @Test
    void restoration_and_confirmation_race_produces_single_terminal_state() throws Exception {
        fixtures.generator("GEN-1");
        prepareStationForDispatch("BS-1");
        service.handleDispatch(new DispatchRequest("REQ-D1", "BS-1", T0, "GEN-1"));
        clock.advanceSeconds(10 * 60);
        Instant restoredAt = clock.now();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> confirmFuture = pool.submit(() -> {
                ready.countDown();
                start.await();
                return service.handleConfirmation(new ConfirmationRequest("REQ-C1", "REQ-D1"));
            });
            Future<Object> restoreFuture = pool.submit(() -> {
                ready.countDown();
                start.await();
                return service.handleRestoration(new RestorationRequest("REQ-S1", "BS-1", restoredAt));
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            // 恢复必须完成；确认成功或被判迟到确认，都不允许回滚恢复。
            restoreFuture.get(15, TimeUnit.SECONDS);
            try {
                confirmFuture.get(15, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 锁等待下确认侧也可能以冲突结束；最终状态仍是下面的唯一终态。
            }
        } finally {
            pool.shutdownNow();
        }

        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM dispatch_order WHERE request_id = 'REQ-D1'", String.class);
        String eventStatus = jdbc.queryForObject(
                "SELECT status FROM outage_event WHERE station_id = " +
                        "(SELECT id FROM base_station WHERE station_code = 'BS-1')", String.class);
        String genStatus = jdbc.queryForObject(
                "SELECT status FROM generator WHERE gen_code = 'GEN-1'", String.class);

        // 唯一终态：调度单解除、事件恢复，绝不能既解除又保持发电中。
        assertThat(orderStatus).isEqualTo("RELEASED");
        assertThat(eventStatus).isEqualTo("RESTORED");
        assertThat(genStatus).isEqualTo("IDLE");
        assertThat(count("generator_active_order")).isZero();
        assertThat(count("station_active_order")).isZero();
    }

    @Test
    void concurrent_reassignments_after_timeout_put_only_one_new_generator_en_route() throws Exception {
        fixtures.generator("GEN-1");
        fixtures.generator("GEN-2");
        fixtures.generator("GEN-3");
        prepareStationForDispatch("BS-1");
        service.handleDispatch(new DispatchRequest("REQ-D1", "BS-1", T0, "GEN-1"));
        clock.advanceSeconds(30 * 60);
        service.handleConfirmation(new ConfirmationRequest("REQ-C0", "REQ-D1"));

        List<Callable<Object>> tasks = List.of(
                () -> service.handleReassignment(
                        new ReassignmentRequest("REQ-X1", "REQ-D1", "GEN-2")),
                () -> service.handleReassignment(
                        new ReassignmentRequest("REQ-X2", "REQ-D1", "GEN-3"))
        );
        List<Future<Object>> futures = runConcurrently(tasks);

        int success = 0;
        int rejected = 0;
        for (Future<Object> future : futures) {
            try {
                future.get(15, TimeUnit.SECONDS);
                success++;
            } catch (Exception e) {
                assertThat(e.getCause()).isInstanceOf(UnprocessableException.class);
                rejected++;
            }
        }
        assertThat(success).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);
        // 同站始终只有一台油机在途。
        assertThat(count("generator_active_order")).isEqualTo(1);
        int enRoute = jdbc.queryForObject(
                "SELECT COUNT(*) FROM generator WHERE status = 'DISPATCHED'", Integer.class);
        assertThat(enRoute).isEqualTo(1);
        int idle = jdbc.queryForObject(
                "SELECT COUNT(*) FROM generator WHERE status = 'IDLE'", Integer.class);
        assertThat(idle).isEqualTo(2); // GEN-1 超时释放 + 一台未被选用
    }

    private void prepareStationForDispatch(String stationCode) {
        fixtures.station(stationCode, "3000", "2000");
        service.handleAlert(new AlertRequest("REQ-A-" + stationCode, stationCode, T0));
        service.handleReading(new ReadingRequest("REQ-R-" + stationCode, stationCode, T0,
                new BigDecimal("1000")));
    }

    private List<Future<Object>> runConcurrently(List<Callable<Object>> tasks) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<Object>> futures = tasks.stream()
                    .map(task -> pool.submit((Callable<Object>) () -> {
                        ready.countDown();
                        start.await();
                        return task.call();
                    }))
                    .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return futures;
        } finally {
            pool.shutdown();
        }
    }
}
