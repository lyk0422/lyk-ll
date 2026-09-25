package com.example.starter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.error.ApiException;
import com.example.starter.web.ElevatorController;
import com.example.starter.web.MaintenanceController;
import com.example.starter.web.RescueController;
import com.example.starter.web.dto.ApiDtos.DispatchRequest;
import com.example.starter.web.dto.ApiDtos.MaintenanceRequest;
import com.example.starter.web.dto.ApiDtos.ReportRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发边界：并发接报唯一受理；强制停梯（维保逾期锁定）与在途派单竞态形成唯一终态。
 * 通过 CountDownLatch 协调真实并发，全部以超时等待与最终数据断言验证。
 */
class ConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private RescueController rescueController;
    @Autowired
    private MaintenanceController maintenanceController;
    @Autowired
    private ElevatorController elevatorController;

    private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");

    @Test
    @DisplayName("并发接报同一电梯：唯一受理，其余409，仅一单进行中")
    void concurrentReportsSingleAcceptance() throws Exception {
        long e1 = createElevator("CC-RPT", LocalDate.of(2026, 1, 1));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "CC-r" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    ResponseEntity<String> resp = rescueController.report(
                            new ReportRequest(requestId, e1, T0));
                    return resp.getStatusCode().value();
                } catch (ApiException ex) {
                    return ex.status().value();
                }
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程未就绪");
        start.countDown();
        int ok = 0;
        int conflict = 0;
        for (Future<Integer> f : futures) {
            int status = f.get(15, TimeUnit.SECONDS);
            if (status == 200) {
                ok++;
            } else if (status == 409) {
                conflict++;
            }
        }
        pool.shutdownNow();
        assertEquals(1, ok, "并发接报应唯一受理");
        assertEquals(threads - 1, conflict, "其余并发接报应 409");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rescue_order WHERE elevator_id=?", Integer.class, e1);
        assertEquals(1, count, "同一电梯仅允许一单进行中");
        assertEquals("STOPPED", elevator(e1).text("status"));
    }

    @Test
    @DisplayName("停梯（维保逾期锁定）与在途派单竞态：形成唯一终态，不得既停梯又继续派单")
    void dispatchVsMaintenanceLockRace() throws Exception {
        long e1 = createElevator("CC-RACE", LocalDate.of(2026, 1, 1));
        long s1 = createStaff("C-RC-1", true);
        long s2 = createStaff("C-RC-2", true);
        long rescueId = report("CC-race-report", e1, T0).id();
        assertEquals("STOPPED", elevator(e1).text("status"), "接报后困人停梯");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> dispatchFuture = pool.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                return rescueController.dispatch(rescueId, new DispatchRequest("CC-race-d1", List.of(s1, s2)))
                        .getStatusCode().value();
            } catch (ApiException ex) {
                return ex.status().value();
            }
        });
        Future<Integer> lockFuture = pool.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                // 投运 2026-01-01，应维保截止 2026-01-16，2026-01-20 执行属逾期 → 锁定
                return maintenanceController.register(
                        new MaintenanceRequest("CC-race-m1", e1, LocalDate.of(2026, 1, 20), null, null))
                        .getStatusCode().value();
            } catch (ApiException ex) {
                return ex.status().value();
            }
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程未就绪");
        start.countDown();
        int dispatchStatus = dispatchFuture.get(15, TimeUnit.SECONDS);
        int lockStatus = lockFuture.get(15, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(200, lockStatus, "逾期维保登记应入账");
        String rescueStatus = getJson("/api/rescues/" + rescueId).text("status");
        if (dispatchStatus == 200) {
            assertEquals("DISPATCHED", rescueStatus, "派单胜出则在途救援单继续推进");
        } else {
            assertEquals(409, dispatchStatus, "锁定在先则派单必须被拒绝");
            assertEquals("REPORTED", rescueStatus, "派单被拒则救援单保持已接报");
        }
        assertEquals("STOPPED", elevator(e1).text("status"), "终态电梯必须停梯");
        assertTrue(elevator(e1).bool("keySupervision"), "逾期应列入重点监察");
        assertTrue(chainHas(e1, "STOP", "MAINTENANCE_OVERDUE"), "停复梯链应含逾期锁定");
        Integer overdue = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_record WHERE elevator_id=? AND result='OVERDUE'",
                Integer.class, e1);
        assertEquals(1, overdue, "逾期维保应唯一入账");
    }
}
