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
import com.example.starter.web.dto.ApiDtos.AttemptCompleteRequest;
import com.example.starter.web.dto.ApiDtos.PlanScanRequest;
import com.example.starter.web.dto.ApiDtos.ReportRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 周期维保并发边界（真实 H2 行锁/唯一约束，CountDownLatch 协调并发）：
 * 并发扫描同周期只产生一项义务；完成确认与再次救援竞争时登记记录、终结义务、释放资源保持一致。
 */
class MaintenancePlanConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private MaintenancePlanController planController;
    @Autowired
    private RescueController rescueController;

    private static final LocalDate COMMISSIONED = LocalDate.of(2026, 1, 1);
    private static final Instant DISPATCHABLE = Instant.parse("2026-01-15T00:00:00Z");
    private static final Instant W_START = Instant.parse("2026-01-15T00:00:00Z");
    private static final Instant W_END = Instant.parse("2026-01-16T12:00:00Z");

    @Test
    @DisplayName("并发扫描同一电梯同一到期周期：仅产生一项义务")
    void concurrentScanCreatesOneObligation() throws Exception {
        long e1 = createElevator("PC-SCAN", COMMISSIONED);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String key = "PC-scan-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    ResponseEntity<String> resp = planController.scan(new PlanScanRequest(key, DISPATCHABLE));
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
            int status = f.get(20, TimeUnit.SECONDS);
            if (status == 200) {
                ok++;
            }
        }
        pool.shutdownNow();
        assertEquals(threads, ok, "重复扫描全部幂等成功，不多派不报错");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_obligation WHERE elevator_id=?", Integer.class, e1);
        assertEquals(1, count, "同一到期周期唯一义务");

        // 仅一个扫描响应实际携带新建义务，其余为空列表
        Resp obligations = getJson("/api/maintenance-plans?elevatorId=" + e1);
        assertEquals(1, obligations.body().size());
    }

    @Test
    @DisplayName("完成确认与接报救援竞争：记录登记、义务终结与资源释放终态一致")
    void completeVsRescueRace() throws Exception {
        long e1 = createElevator("PC-RACE", COMMISSIONED);
        long s1 = createStaff("C-PC-1", true);
        long s2 = createStaff("C-PC-2", true);
        planController.scan(new PlanScanRequest("PC-scan", DISPATCHABLE));
        long obId = firstObligationId(e1);
        long attemptId = planDispatch("PC-d", e1, obId, List.of(s1, s2), twoItems(),
                W_START, W_END, DISPATCHABLE.plusSeconds(60)).id();
        assertEquals(200, reportItem(attemptId, "PC-i1", "I-1", true, 1, DISPATCHABLE.plusSeconds(120)).status());
        assertEquals(200, reportItem(attemptId, "PC-i2", "I-2", true, 2, DISPATCHABLE.plusSeconds(180)).status());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> completeFuture = pool.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                return planController.complete(attemptId, new AttemptCompleteRequest(
                        "PC-complete", List.of(s1, s2), 3, DISPATCHABLE.plusSeconds(240))).getStatusCode().value();
            } catch (ApiException ex) {
                return ex.status().value();
            }
        });
        Future<Integer> reportFuture = pool.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                return rescueController.report(new ReportRequest(
                        "PC-rescue", e1, DISPATCHABLE.plusSeconds(300))).getStatusCode().value();
            } catch (ApiException ex) {
                return ex.status().value();
            }
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程未就绪");
        start.countDown();
        int completeStatus = completeFuture.get(20, TimeUnit.SECONDS);
        int reportStatus = reportFuture.get(20, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertEquals(200, completeStatus);
        assertEquals(200, reportStatus);

        String obStatus = planObligation(obId).text("status");
        String attemptStatus = planAttempt(attemptId).text("status");
        Integer validRecords = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_record WHERE elevator_id=? AND result='COMPLIANT' "
                        + "AND attempt_id=?", Integer.class, e1, attemptId);
        Integer activeRescues = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rescue_order WHERE active_elevator_key=?", Integer.class, e1);
        Integer rescueCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rescue_order WHERE elevator_id=?", Integer.class, e1);

        if ("FULFILLED".equals(obStatus)) {
            // 完成胜出：记录唯一登记，救援仍可接报（义务已终结，不影响救援流程）
            assertEquals("COMPLETED", attemptStatus);
            assertEquals(1, validRecords, "完成胜出须登记一条有效维保记录");
            assertEquals(1, rescueCount);
            assertEquals(1, activeRescues, "救援接报成功并占坑");
        } else {
            // 救援胜出：尝试挂起、义务未终结、无维保记录
            assertEquals("OPEN", obStatus);
            assertEquals("SUSPENDED", attemptStatus);
            assertEquals(0, validRecords, "救援胜出时不得登记维保完成记录");
            assertEquals(1, rescueCount);
        }
        // 同窗资源占用终态：义务终结或尝试挂起后，均无 PLANNED 尝试占用人员
        Integer planned = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_attempt WHERE elevator_id=? AND status='PLANNED'",
                Integer.class, e1);
        assertEquals(0, planned, "竞争后不得残留占用资源的进行中尝试");
    }

    @Test
    @DisplayName("并发双人回报同一清单项：版本只加一次，事实仅生效一次")
    void concurrentItemReportVersionOnce() throws Exception {
        long e1 = createElevator("PC-ITEM", COMMISSIONED);
        long s1 = createStaff("C-PCI-1", true);
        long s2 = createStaff("C-PCI-2", true);
        planController.scan(new PlanScanRequest("PC-item-scan", DISPATCHABLE));
        long obId = firstObligationId(e1);
        long attemptId = planDispatch("PC-item-d", e1, obId, List.of(s1, s2), twoItems(),
                W_START, W_END, DISPATCHABLE.plusSeconds(60)).id();

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String key = "PC-item-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    return planController.reportItem(attemptId,
                            new com.example.starter.web.dto.ApiDtos.AttemptItemRequest(
                                    key, "I-1", true, 1, DISPATCHABLE.plusSeconds(120)))
                            .getStatusCode().value();
                } catch (ApiException ex) {
                    return ex.status().value();
                }
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        int ok = 0;
        int conflict = 0;
        for (Future<Integer> f : futures) {
            int status = f.get(20, TimeUnit.SECONDS);
            if (status == 200) {
                ok++;
            } else if (status == 409) {
                conflict++;
            }
        }
        pool.shutdownNow();
        // 不同 requestId：首个成功版本 1→2，其余 expectedVersion=1 与实际版本冲突
        assertEquals(1, ok, "同一项只允许一次成功版本变更");
        assertEquals(threads - 1, conflict, "其余并发回报须版本冲突");
        Integer version = jdbc.queryForObject(
                "SELECT version FROM maintenance_attempt WHERE id=?", Integer.class, attemptId);
        assertEquals(2, version, "版本只加一次");
    }
}
