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
import com.example.starter.web.MaintenanceAttemptController;
import com.example.starter.web.MaintenancePlanController;
import com.example.starter.web.RescueController;
import com.example.starter.web.dto.ApiDtos.AttemptCompleteRequest;
import com.example.starter.web.dto.ApiDtos.AttemptDispatchRequest;
import com.example.starter.web.dto.ApiDtos.ReportRequest;
import com.example.starter.web.dto.ApiDtos.ScanRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 履约续派并发边界：并发扫描同周期唯一义务；并发派发唯一胜出；
 * 完成与再次救援竞争形成一致终态。CountDownLatch 协调真实并发，超时等待 + 最终数据断言。
 */
class MaintenancePlanConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private MaintenancePlanController planController;
    @Autowired
    private MaintenanceAttemptController attemptController;
    @Autowired
    private RescueController rescueController;

    private static final Instant OPEN_AT = Instant.parse("2026-01-15T00:00:00Z");
    private static final Instant WS = Instant.parse("2026-01-15T08:00:00Z");
    private static final Instant WE = Instant.parse("2026-01-15T12:00:00Z");

    @Test
    @DisplayName("并发扫描：同一电梯同一到期周期仅一项义务")
    void concurrentScansCreateSingleObligation() throws Exception {
        long e1 = createElevator("MPC-SCAN", LocalDate.of(2026, 1, 1));
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "MPCS-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    ResponseEntity<String> resp = planController.scan(new ScanRequest(requestId, OPEN_AT));
                    return resp.getStatusCode().value();
                } catch (ApiException ex) {
                    return ex.status().value();
                }
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程未就绪");
        start.countDown();
        int ok = 0;
        for (Future<Integer> f : futures) {
            if (f.get(15, TimeUnit.SECONDS) == 200) {
                ok++;
            }
        }
        pool.shutdownNow();
        assertEquals(threads, ok, "并发扫描均应受理成功");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_obligation WHERE elevator_id=?", Integer.class, e1);
        assertEquals(1, count, "同一电梯同一到期周期只能一项履约义务");
    }

    @Test
    @DisplayName("并发派发同一义务：唯一胜出，其余版本冲突409，仅一次尝试占资源")
    void concurrentDispatchSingleWinner() throws Exception {
        long e1 = createElevator("MPC-DSP", LocalDate.of(2026, 1, 1));
        long s1 = createStaff("MPC-D-1", true);
        long s2 = createStaff("MPC-D-2", true);
        long s3 = createStaff("MPC-D-3", true);
        long s4 = createStaff("MPC-D-4", true);
        long ob = scanOpenObligation("MPCD", e1, OPEN_AT);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<List<Long>> staffPairs = List.of(List.of(s1, s2), List.of(s3, s4));
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String requestId = "MPCD-d" + i;
            List<Long> staff = staffPairs.get(i);
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    return planController.dispatch(ob,
                            new AttemptDispatchRequest(requestId, 1L, staff, WS, WE))
                            .getStatusCode().value();
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
        assertEquals(1, ok, "并发派发应唯一胜出");
        assertEquals(1, conflict, "落败方应版本冲突409");
        Integer attempts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_attempt WHERE obligation_id=?", Integer.class, ob);
        assertEquals(1, attempts, "仅一次尝试占用资源");
        assertEquals(2, obligation(ob).body().get("version").asLong(), "义务版本仅成功变更一次");
    }

    @Test
    @DisplayName("完成与再次救援竞争：登记记录、终结义务、释放资源同事务，终态一致")
    void completeVsRescueRaceIsConsistent() throws Exception {
        long e1 = createElevator("MPC-RACE", LocalDate.of(2026, 1, 1));
        long s1 = createStaff("MPC-R-1", true);
        long s2 = createStaff("MPC-R-2", true);
        long ob = scanOpenObligation("MPCR", e1, OPEN_AT);
        long at = dispatchAttempt("MPCR-d", ob, 1, List.of(s1, s2), WS, WE).id();
        assertEquals(200, startAttempt("MPCR-s", at, 1, WS).status());
        assertEquals(200, reportItems("MPCR-i", at, 2,
                List.of("BRAKE_CHECK", "DOOR_INTERLOCK", "ALARM_TEST")).status());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> completeFuture = pool.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                return attemptController.complete(at,
                        new AttemptCompleteRequest("MPCR-c", 3L, List.of(s1, s2),
                                Instant.parse("2026-01-15T10:00:00Z"))).getStatusCode().value();
            } catch (ApiException ex) {
                return ex.status().value();
            }
        });
        Future<Integer> reportFuture = pool.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                return rescueController.report(
                        new ReportRequest("MPCR-r", e1, Instant.parse("2026-01-15T09:30:00Z")))
                        .getStatusCode().value();
            } catch (ApiException ex) {
                return ex.status().value();
            }
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程未就绪");
        start.countDown();
        int completeStatus = completeFuture.get(15, TimeUnit.SECONDS);
        int reportStatus = reportFuture.get(15, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(200, reportStatus, "救援接报必须受理");
        String attemptStatus = attempt(at).text("status");
        String obligationStatus = obligation(ob).text("status");
        Integer compliant = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_record WHERE elevator_id=? AND result='COMPLIANT'",
                Integer.class, e1);
        if (completeStatus == 200) {
            // 完成胜出：记录、义务终结、资源释放同事务生效；随后救援不再挂起已完成尝试
            assertEquals("COMPLETED", attemptStatus);
            assertEquals("FULFILLED", obligationStatus);
            assertEquals(1, compliant, "完成胜出须登记唯一有效记录");
            assertEquals("2026-01-15", elevator(e1).text("lastMaintenanceDay"));
        } else {
            // 救援胜出：尝试被挂起，完成因版本冲突失败；义务保持 OPEN，无有效记录
            assertEquals(409, completeStatus, "完成落败应版本冲突409");
            assertEquals("SUSPENDED", attemptStatus);
            assertEquals("OPEN", obligationStatus);
            assertEquals(0, compliant, "挂起后不得登记有效记录");
            // 旧尝试以新版本回报只记迟到
            var late = completeAttempt("MPCR-c2", at, 4, List.of(s1, s2),
                    Instant.parse("2026-01-15T10:00:00Z"));
            assertEquals(200, late.status());
            assertEquals("LATE_REPORTED", late.text("status"));
            Integer lateEvents = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM maintenance_record WHERE elevator_id=? AND result='LATE_EVENT'",
                    Integer.class, e1);
            assertEquals(1, lateEvents);
            assertEquals("OPEN", obligation(ob).text("status"), "迟到回报不得终结义务");
        }
        Integer rescues = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rescue_order WHERE elevator_id=?", Integer.class, e1);
        assertEquals(1, rescues, "两种终态下救援单均唯一受理");
    }
}
