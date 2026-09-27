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
import com.example.starter.web.ObligationController;
import com.example.starter.web.RescueController;
import com.example.starter.web.dto.ApiDtos.ObligationAllocateRequest;
import com.example.starter.web.dto.ApiDtos.ObligationCompleteRequest;
import com.example.starter.web.dto.ApiDtos.ObligationItemsRequest;
import com.example.starter.web.dto.ApiDtos.ObligationStartRequest;
import com.example.starter.web.dto.ApiDtos.ReportRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 履约续派并发/幂等边界（真实 H2 事务，CountDownLatch 协调并发并带超时断言）：
 * 完成与再次接报竞争必须形成一致终态；重复扫描唯一义务；同窗双人占用不重复；
 * 同键并发派发只产生一个尝试并返回首次冻结结果。
 */
class ObligationConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private ObligationController obligationController;
    @Autowired
    private RescueController rescueController;

    private static final LocalDate COMMISSIONED = LocalDate.of(2026, 1, 1);
    private static final Instant DUE = Instant.parse("2026-01-16T00:00:00Z");
    private static final Instant EARLIEST = DUE.minusSeconds(24 * 3600);

    /** 准备：义务已派发、已开始、四项清单全部回报，处于可完成状态。返回 [elevatorId, obligationId, attemptId]。 */
    private long[] preparedInProgressAttempt(long s1, long s2, String prefix) throws Exception {
        long e1 = createElevator("CC-OB-" + prefix, COMMISSIONED);
        assertEquals(200, scanObligations(e1, EARLIEST).status());
        long ob = jdbc.queryForObject(
                "SELECT id FROM maintenance_obligation WHERE elevator_id=?", Long.class, e1);
        Instant ws = EARLIEST.plusSeconds(60);
        ResponseEntity<String> a = obligationController.allocate(ob,
                new ObligationAllocateRequest(prefix + "-alloc", 1L, List.of(s1, s2), ws, ws.plusSeconds(7200)));
        assertEquals(200, a.getStatusCode().value());
        long attemptId = jdbc.queryForObject(
                "SELECT id FROM maintenance_attempt WHERE obligation_id=? ORDER BY seq DESC FETCH FIRST 1 ROW ONLY",
                Long.class, ob);
        assertEquals(200, obligationController.start(attemptId,
                new ObligationStartRequest(prefix + "-start", 1L, ws)).getStatusCode().value());
        assertEquals(200, obligationController.items(attemptId,
                new ObligationItemsRequest(prefix + "-items", 2L, ALL_CHECKLIST, ws.plusSeconds(300)))
                .getStatusCode().value());
        return new long[]{e1, ob, attemptId};
    }

    @Test
    @DisplayName("完成与再次接报救援竞争：终态一致——要么履约完成且救援另立，要么挂起后仅记迟到，不允许两者兼有")
    void completeVsReportRaceIsConsistent() throws Exception {
        long s1 = createStaff("CC-R1", true);
        long s2 = createStaff("CC-R2", true);
        long[] ctx = preparedInProgressAttempt(s1, s2, "RACE");
        long e1 = ctx[0];
        long ob = ctx[1];
        long attemptId = ctx[2];
        Instant at = EARLIEST.plusSeconds(1200);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        futures.add(pool.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                return obligationController.complete(attemptId,
                        new ObligationCompleteRequest("RACE-done", 3L, List.of(s1, s2), ALL_CHECKLIST, at))
                        .getStatusCode().value();
            } catch (ApiException ex) {
                return ex.status().value();
            }
        }));
        futures.add(pool.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                return rescueController.report(new ReportRequest("RACE-report", e1, at)).getStatusCode().value();
            } catch (ApiException ex) {
                return ex.status().value();
            }
        }));
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        for (Future<Integer> f : futures) {
            assertEquals(200, f.get(20, TimeUnit.SECONDS), "竞争双方都应得到确定的成功终态，而非异常");
        }
        pool.shutdownNow();

        String obStatus = jdbc.queryForObject(
                "SELECT status FROM maintenance_obligation WHERE id=?", String.class, ob);
        String attemptStatus = jdbc.queryForObject(
                "SELECT status FROM maintenance_attempt WHERE id=?", String.class, attemptId);
        Integer validRecords = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_record WHERE elevator_id=? AND result IN ('COMPLIANT','OVERDUE')",
                Integer.class, e1);
        Integer lateRecords = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_record WHERE elevator_id=? AND result='LATE_EVENT'",
                Integer.class, e1);
        Integer activeRescues = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rescue_order WHERE elevator_id=? AND active_elevator_key=?",
                Integer.class, e1, e1);
        Integer occupiedWindows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_attempt_staff WHERE attempt_id=?", Integer.class, attemptId);

        if ("FULFILLED".equals(obStatus)) {
            // 完成胜出：有效记录恰好一条，尝试 COMPLETED，资源释放；接报另立进行中救援
            assertEquals("COMPLETED", attemptStatus);
            assertEquals(1, validRecords, "履约成功只登记一条有效记录");
            assertEquals(0, lateRecords, "完成胜出不得再有迟到记录");
            assertEquals(1, activeRescues, "随后接报应另立一单进行中救援");
            assertEquals(0, occupiedWindows, "完成须释放资源");
        } else {
            // 接报胜出：义务未终结，尝试挂起/迟到，只记迟到事件，无有效记录
            assertEquals("OPEN", obStatus, "挂起后续派前义务应保持 OPEN");
            assertTrue("SUSPENDED".equals(attemptStatus) || "LATE".equals(attemptStatus),
                    "接报胜出时尝试应为 SUSPENDED 或被随后完成回报置为 LATE，实际 " + attemptStatus);
            assertEquals(0, validRecords, "中断的回报不得登记为有效维保");
            assertEquals(1, activeRescues);
            assertEquals(0, occupiedWindows, "挂起须释放人员");
            if ("LATE".equals(attemptStatus)) {
                assertEquals(1, lateRecords, "旧尝试迟到回报恰好一条");
            }
        }
    }

    @Test
    @DisplayName("并发重复扫描同一电梯：只生成一项义务，重复扫描不多派")
    void concurrentScanCreatesSingleObligation() throws Exception {
        long e1 = createElevator("CC-SCAN", COMMISSIONED);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(5, TimeUnit.SECONDS);
                    obligationController.scan(e1, EARLIEST.toString());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        Integer obligations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_obligation WHERE elevator_id=?", Integer.class, e1);
        assertEquals(1, obligations, "同一到期周期只允许一项履约义务");
        Integer checklist = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_obligation_checklist c "
                        + "JOIN maintenance_obligation o ON o.id=c.obligation_id WHERE o.elevator_id=?",
                Integer.class, e1);
        assertEquals(4, checklist);
    }

    @Test
    @DisplayName("并发同窗占用同一持证人员：唯一占用，落败方422且整批不占资源")
    void concurrentSameStaffWindowSingleBooking() throws Exception {
        long s1 = createStaff("CC-W-1", true);
        long s2 = createStaff("CC-W-2", true);
        long s3 = createStaff("CC-W-3", true);
        long e1 = createElevator("CC-W-A", COMMISSIONED);
        long e2 = createElevator("CC-W-B", COMMISSIONED);
        assertEquals(200, scanObligations(e1, EARLIEST).status());
        assertEquals(200, scanObligations(e2, EARLIEST).status());
        long ob1 = jdbc.queryForObject("SELECT id FROM maintenance_obligation WHERE elevator_id=?", Long.class, e1);
        long ob2 = jdbc.queryForObject("SELECT id FROM maintenance_obligation WHERE elevator_id=?", Long.class, e2);
        Instant ws = EARLIEST.plusSeconds(60);
        Instant we = ws.plusSeconds(3600);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        futures.add(submitAllocate(pool, ready, start, ob1, "CCW-a", List.of(s1, s2), ws, we));
        futures.add(submitAllocate(pool, ready, start, ob2, "CCW-b", List.of(s1, s3), ws, we));
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        int ok = 0;
        int rejected = 0;
        for (Future<Integer> f : futures) {
            int code = f.get(20, TimeUnit.SECONDS);
            if (code == 200) {
                ok++;
            } else if (code == 422) {
                rejected++;
            }
        }
        pool.shutdownNow();
        assertEquals(1, ok, "同窗只能有一项尝试占用 s1");
        assertEquals(1, rejected, "落败方应被业务拒绝");
        Integer s1Bookings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_attempt_staff WHERE staff_id=? "
                        + "AND attempt_id IN (SELECT id FROM maintenance_attempt WHERE status IN ('ALLOCATED','IN_PROGRESS'))",
                Integer.class, s1);
        assertEquals(1, s1Bookings, "同一人员同窗不得重复占用");
        Integer totalAttempts = jdbc.queryForObject("SELECT COUNT(*) FROM maintenance_attempt", Integer.class);
        assertEquals(1, totalAttempts, "落败方整批不占资源，不生成尝试");
    }

    @Test
    @DisplayName("并发同键同参派发：仅一个尝试，全部返回首次冻结结果；异参409")
    void concurrentSameKeyAllocateReplaysFirst() throws Exception {
        long s1 = createStaff("CC-I-1", true);
        long s2 = createStaff("CC-I-2", true);
        long e1 = createElevator("CC-IDEM", COMMISSIONED);
        assertEquals(200, scanObligations(e1, EARLIEST).status());
        long ob = jdbc.queryForObject("SELECT id FROM maintenance_obligation WHERE elevator_id=?", Long.class, e1);
        Instant ws = EARLIEST.plusSeconds(60);
        Instant we = ws.plusSeconds(3600);

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(submitAllocate(pool, ready, start, ob, "CCIDEM-k1", List.of(s1, s2), ws, we));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        for (Future<Integer> f : futures) {
            assertEquals(200, f.get(20, TimeUnit.SECONDS), "并发同键都应返回首次冻结结果");
        }
        pool.shutdownNow();
        Integer attempts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_attempt WHERE obligation_id=?", Integer.class, ob);
        assertEquals(1, attempts, "同键并发不得重复派发");
        Integer records = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE scope='OBLIGATION_ALLOCATE' AND request_id='CCIDEM-k1'",
                Integer.class);
        assertEquals(1, records, "幂等记录唯一");

        // 同键异参 409（直接调控制器抛出 ApiException，HTTP 层由全局处理转为 409）
        try {
            obligationController.allocate(ob,
                    new ObligationAllocateRequest("CCIDEM-k1", 1L, List.of(s1, s2), ws, we.plusSeconds(60)));
            org.junit.jupiter.api.Assertions.fail("同键异参应抛 409");
        } catch (ApiException ex) {
            assertEquals(409, ex.status().value());
            assertEquals("IDEMPOTENCY_CONFLICT", ex.code());
        }
    }

    private Future<Integer> submitAllocate(ExecutorService pool, CountDownLatch ready, CountDownLatch start,
                                           long obligationId, String requestId, List<Long> staff,
                                           Instant ws, Instant we) {
        return pool.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                return obligationController.allocate(obligationId,
                        new ObligationAllocateRequest(requestId, 1L, staff, ws, we)).getStatusCode().value();
            } catch (ApiException ex) {
                return ex.status().value();
            }
        });
    }
}
