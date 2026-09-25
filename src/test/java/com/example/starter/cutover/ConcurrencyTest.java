package com.example.starter.cutover;

import com.example.starter.cutover.api.ApplicationResponse;
import com.example.starter.cutover.api.ApplyRequest;
import com.example.starter.cutover.api.ReportRequest;
import com.example.starter.cutover.api.ReportResponse;
import com.example.starter.cutover.api.RollbackResponse;
import com.example.starter.cutover.api.SealResponse;
import com.example.starter.cutover.service.CutoverService;
import com.example.starter.cutover.support.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发与竞态：回退/封板唯一终态、同设备并发审批唯一批准、同键并发重放唯一记录。
 * 所有并发用例设置超时，断言最终数据库状态而非日志输出。
 */
class ConcurrencyTest extends AbstractCutoverTest {

    @Autowired
    private CutoverService cutoverService;

    private Long prepareExecuting(String requestId, LocalDateTime start, LocalDateTime end) {
        ApplicationResponse app = cutoverService.apply(
                applyRequest(requestId, "DEV-CORE-SW-01", "NORMAL", start, end, defaultSteps()));
        cutoverService.analyze(app.id());
        cutoverService.approve(app.id());
        cutoverService.execute(app.id());
        return app.id();
    }

    private <T> T runConcurrently(Callable<T> a, Callable<T> b, List<Throwable> errors)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Callable<T> wrappedA = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return a.call();
            };
            Callable<T> wrappedB = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return b.call();
            };
            Future<T> fa = pool.submit(wrappedA);
            Future<T> fb = pool.submit(wrappedB);
            T ra = null;
            T rb = null;
            try {
                ra = fa.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                errors.add(e);
            }
            try {
                rb = fb.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                errors.add(e);
            }
            @SuppressWarnings("unchecked")
            T result = ra != null ? ra : rb;
            return result;
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentEvaluateOnOverdueWindowProducesSingleRollbackRecord() throws Exception {
        Long id = prepareExecuting("REQ-C-EVAL", BASE_TIME, BASE_TIME.plusHours(1));
        cutoverService.report(id, 1, new ReportRequest("R-S1", "START", null));
        // 越过窗口终点，步骤 1 仍 RUNNING，步骤 2/3 PENDING
        setClock(BASE_TIME.plusHours(1).plusSeconds(1));

        List<Throwable> errors = new java.util.ArrayList<>();
        runConcurrently(() -> cutoverService.evaluate(id), () -> cutoverService.evaluate(id), errors);
        assertTrue(errors.isEmpty(), "并发巡检不应抛错: " + errors);

        Integer rollbackCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rollback_record WHERE cutover_id = ?", Integer.class, id);
        assertEquals(1, rollbackCount, "唯一约束保证只产生一条回退记录");
        assertEquals("WINDOW_OVERDUE", cutoverService.getApplication(id).status());
    }

    @Test
    void raceBetweenFinalReportAndOverdueEvaluateYieldsUniqueTerminal() throws Exception {
        Long id = prepareExecuting("REQ-C-RACE", BASE_TIME, BASE_TIME.plusHours(1));
        cutoverService.report(id, 1, new ReportRequest("R-S1", "START", null));
        cutoverService.report(id, 1, new ReportRequest("R-R1", "RESULT", "SUCCESS"));
        cutoverService.report(id, 2, new ReportRequest("R-S2", "START", null));
        cutoverService.report(id, 2, new ReportRequest("R-R2", "RESULT", "SUCCESS"));
        // 验证点在终点前 5 分钟开始，恰在窗口终点回报：未超时且终点口径有效
        setClock(BASE_TIME.plusHours(1).minusMinutes(5));
        cutoverService.report(id, 3, new ReportRequest("R-S3", "START", null));
        // 恰在窗口终点：最后一步回报有效；同时巡检认为窗口已到点
        setClock(BASE_TIME.plusHours(1));

        List<Throwable> errors = new java.util.ArrayList<>();
        runConcurrently(
                () -> cutoverService.report(id, 3, new ReportRequest("R-R3", "RESULT", "SUCCESS")),
                () -> cutoverService.evaluate(id),
                errors);
        assertTrue(errors.isEmpty(), "竞态双方都应正常返回: " + errors);

        ApplicationResponse detail = cutoverService.getApplication(id);
        String status = detail.status();
        assertTrue(status.equals("VERIFIED") || status.equals("WINDOW_OVERDUE"),
                "终态必须是二者之一，实际: " + status);

        // 终态唯一性：要么验证通过可封板，要么已回退且不得封板，不得既回退又封板
        Integer rollbackCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rollback_record WHERE cutover_id = ?", Integer.class, id);
        if (status.equals("VERIFIED")) {
            assertEquals(0, rollbackCount);
            SealResponse seal = cutoverService.seal(id);
            assertEquals("SEALED", seal.status());
            Integer rollbackAfter = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM rollback_record WHERE cutover_id = ?", Integer.class, id);
            assertEquals(0, rollbackAfter, "封板后不得再出现回退记录");
        } else {
            assertEquals(1, rollbackCount);
            ConflictException ex = org.junit.jupiter.api.Assertions.assertThrows(
                    ConflictException.class, () -> cutoverService.seal(id));
            assertEquals("TERMINAL_CONFLICT", ex.getCode());
            assertEquals("WINDOW_OVERDUE", cutoverService.getApplication(id).status());
        }
    }

    @Test
    void concurrentApprovalOnSameDeviceApprovesOnlyOne() throws Exception {
        ApplicationResponse a = cutoverService.apply(applyRequest("REQ-C-A", "DEV-CORE-SW-01",
                "NORMAL", BASE_TIME, BASE_TIME.plusHours(2), defaultSteps()));
        ApplicationResponse b = cutoverService.apply(applyRequest("REQ-C-B", "DEV-CORE-SW-01",
                "NORMAL", BASE_TIME.plusMinutes(30), BASE_TIME.plusHours(2), defaultSteps()));
        cutoverService.analyze(a.id());
        cutoverService.analyze(b.id());

        List<Throwable> errors = new java.util.ArrayList<>();
        runConcurrently(() -> cutoverService.approve(a.id()), () -> cutoverService.approve(b.id()), errors);
        assertTrue(errors.isEmpty(), "并发审批不应抛错: " + errors);

        String sa = cutoverService.getApplication(a.id()).status();
        String sb = cutoverService.getApplication(b.id()).status();
        assertNotEquals(sa, sb, "同设备重叠窗口并发审批只能批准一个");
        assertTrue(sa.equals("APPROVED") || sa.equals("REJECTED"));
        assertTrue(sb.equals("APPROVED") || sb.equals("REJECTED"));
    }

    @Test
    void concurrentSameKeyApplyCreatesSingleRecord() throws Exception {
        ApplyRequest req = applyRequest("REQ-C-IDEM", "DEV-CORE-SW-01", "NORMAL",
                BASE_TIME, BASE_TIME.plusHours(2), defaultSteps());
        List<Throwable> errors = new java.util.ArrayList<>();
        runConcurrently(() -> cutoverService.apply(req), () -> cutoverService.apply(req), errors);
        assertTrue(errors.isEmpty(), "同键同参并发重放不应抛错: " + errors);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cutover_application WHERE request_id = 'REQ-C-IDEM'",
                Integer.class);
        assertEquals(1, count);
        Integer idemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'REQ-C-IDEM'",
                Integer.class);
        assertEquals(1, idemCount);
    }

    @Test
    void concurrentSealIsIdempotentAndTerminal() throws Exception {
        Long id = prepareExecuting("REQ-C-SEAL", BASE_TIME, BASE_TIME.plusHours(4));
        for (int seq = 1; seq <= 3; seq++) {
            cutoverService.report(id, seq, new ReportRequest("R-S" + seq, "START", null));
            cutoverService.report(id, seq, new ReportRequest("R-R" + seq, "RESULT", "SUCCESS"));
        }
        List<Throwable> errors = new java.util.ArrayList<>();
        runConcurrently(() -> cutoverService.seal(id), () -> cutoverService.seal(id), errors);
        assertTrue(errors.isEmpty(), "并发封板应幂等: " + errors);
        assertEquals("SEALED", cutoverService.getApplication(id).status());
        Integer rollbackCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rollback_record WHERE cutover_id = ?", Integer.class, id);
        assertEquals(0, rollbackCount, "封板终态下不得存在回退记录");
    }
}
