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
import com.example.starter.web.MaintenancePlanController;
import com.example.starter.web.RescueController;
import com.example.starter.web.dto.ApiDtos.AttemptDispatchRequest;
import com.example.starter.web.dto.ApiDtos.ConfirmRequest;
import com.example.starter.web.dto.ApiDtos.PlanScanRequest;
import com.example.starter.web.dto.ApiDtos.ReportRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 维保计划并发边界：并发扫描唯一义务；并发派发人员窗口互斥整批不占；
 * 完成与再次救援竞争形成唯一终态（登记记录、终结义务、释放资源同事务）。
 */
class PlanConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private MaintenancePlanController planController;
    @Autowired
    private RescueController rescueController;

    private static final LocalDate COMMISSIONED = LocalDate.of(2026, 1, 1);
    private static final Instant WINDOW_OPEN = Instant.parse("2026-01-15T00:00:00Z");
    private static final Instant WS = Instant.parse("2026-01-15T08:00:00Z");
    private static final Instant WE = Instant.parse("2026-01-15T12:00:00Z");

    @Test
    @DisplayName("并发扫描同一电梯：唯一义务，重复扫描不多派")
    void concurrentScanCreatesSingleObligation() throws Exception {
        long e1 = createElevator("PCC-S", COMMISSIONED);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "PCC-scan-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    return planController.scan(new PlanScanRequest(requestId, e1, WINDOW_OPEN))
                            .getStatusCode().value();
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
        assertEquals(threads, ok, "并发扫描均应受理（创建或重放已有）");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_obligation WHERE elevator_id=?", Integer.class, e1);
        assertEquals(1, count, "同一电梯同一到期周期只能一项义务");
    }

    @Test
    @DisplayName("并发派发同一人员重叠窗口：恰一个成功，其余422且整批不占")
    void concurrentDispatchSameStaffOverlap() throws Exception {
        long e1 = createElevator("PCC-A", COMMISSIONED);
        long e2 = createElevator("PCC-B", COMMISSIONED);
        long s1 = createStaff("PCC-1", true);
        long s2 = createStaff("PCC-2", true);
        long ob1 = scan("PCC-scan1", e1, WINDOW_OPEN).body().get("obligationId").asLong();
        long ob2 = scan("PCC-scan2", e2, WINDOW_OPEN).body().get("obligationId").asLong();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (long obId : new long[]{ob1, ob2}) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    return planController.dispatch(obId,
                            new AttemptDispatchRequest("PCC-d-" + obId, 1L, List.of(s1, s2), WS, WE))
                            .getStatusCode().value();
                } catch (ApiException ex) {
                    return ex.status().value();
                }
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程未就绪");
        start.countDown();
        int ok = 0;
        int rejected = 0;
        for (Future<Integer> f : futures) {
            int status = f.get(15, TimeUnit.SECONDS);
            if (status == 200) {
                ok++;
            } else if (status == 422) {
                rejected++;
            }
        }
        pool.shutdownNow();
        assertEquals(1, ok, "同一人员重叠窗口只能一个派发成功");
        assertEquals(1, rejected, "另一个应 422 整批不占");
        Integer dispatched = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_attempt WHERE status='DISPATCHED'", Integer.class);
        assertEquals(1, dispatched, "仅一个进行中尝试占用人员");
        Integer bookings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_attempt_staff s JOIN maintenance_attempt a "
                        + "ON a.id=s.attempt_id WHERE a.status='DISPATCHED'", Integer.class);
        assertEquals(2, bookings, "胜出方整批占用两人");
    }

    @Test
    @DisplayName("完成与再次救援竞争：唯一终态，登记记录/终结义务/释放资源一致")
    void completionVsRescueRace() throws Exception {
        long e1 = createElevator("PCC-R", COMMISSIONED);
        long s1 = createStaff("PCC-R1", true);
        long s2 = createStaff("PCC-R2", true);
        long obId = scan("PCC-scanR", e1, WINDOW_OPEN).body().get("obligationId").asLong();
        long attemptId = dispatchAttempt(obId, "PCC-dR", 1, List.of(s1, s2), WS, WE).id();
        // 清单全部完成 + 第一人已确认，只剩第二人确认即完成
        long v = 1;
        for (String item : List.of("SYN-CHECK-1", "SYN-CHECK-2", "SYN-CHECK-3")) {
            v = checklist(attemptId, "PCC-c-" + item, v, item, Instant.parse("2026-01-15T09:00:00Z"))
                    .body().get("version").asLong();
        }
        v = confirm(attemptId, "PCC-cf1", v, s1, Instant.parse("2026-01-15T10:00:00Z"))
                .body().get("version").asLong();
        final long expectedVersion = v;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> confirmFuture = pool.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                return planController.confirm(attemptId,
                        new ConfirmRequest("PCC-cf2", expectedVersion, s2,
                                Instant.parse("2026-01-15T10:30:00Z")))
                        .getStatusCode().value();
            } catch (ApiException ex) {
                return ex.status().value();
            }
        });
        Future<Integer> reportFuture = pool.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                return rescueController.report(
                        new ReportRequest("PCC-r1", e1, Instant.parse("2026-01-15T10:15:00Z")))
                        .getStatusCode().value();
            } catch (ApiException ex) {
                return ex.status().value();
            }
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程未就绪");
        start.countDown();
        int confirmStatus = confirmFuture.get(15, TimeUnit.SECONDS);
        int reportStatus = reportFuture.get(15, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(200, reportStatus, "接报救援始终受理");
        assertEquals(200, confirmStatus, "确认回报要么完成要么记迟到，均受理");

        String attemptStatus = attemptDetail(attemptId).body().get("attempt").get("status").asText();
        String obligationStatus = obligation(obId).text("status");
        Integer compliant = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_record WHERE elevator_id=? AND result='COMPLIANT'",
                Integer.class, e1);
        Integer lateEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_record WHERE elevator_id=? AND result='LATE_EVENT'",
                Integer.class, e1);
        if ("COMPLETED".equals(attemptStatus)) {
            // 确认胜出：登记记录、终结义务、释放资源已同事务完成
            assertEquals("FULFILLED", obligationStatus, "完成则义务必须终结");
            assertEquals(1, compliant, "完成则恰一条有效维保记录");
            assertEquals(0, lateEvents, "完成则不得有迟到事件");
            assertEquals("2026-01-15", elevator(e1).text("lastMaintenanceDay"));
        } else {
            // 救援胜出：尝试被挂起，确认回报只记迟到，义务保持未结清
            assertEquals("LATE_CLOSED", attemptStatus, "挂起后旧尝试回报应迟到关闭");
            assertEquals("OPEN", obligationStatus, "救援抢占不改变到期义务");
            assertEquals(0, compliant, "旧尝试回报不得登记有效维保");
            assertEquals(1, lateEvents, "旧尝试回报只记迟到事件");
        }
    }
}
