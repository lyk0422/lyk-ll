package com.example.starter.pv.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.example.starter.pv.TestClockConfig;
import com.example.starter.pv.data.CasePo;
import com.example.starter.pv.data.CaseRepository;
import com.example.starter.pv.domain.CaseStatus;
import com.example.starter.pv.domain.Seriousness;
import com.example.starter.pv.web.BatchResponse;
import com.example.starter.pv.web.CloseResponse;
import com.example.starter.pv.web.FollowUpRequest;
import com.example.starter.pv.web.FollowUpResponse;
import com.example.starter.pv.web.InitialReportRequest;
import com.example.starter.pv.web.InitialReportResponse;
import com.example.starter.pv.web.SubmitBatchRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基于真实 H2（MODE=MySQL）的业务流程、事务与并发集成测试。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class CaseServiceIntegrationTest {

    @Autowired
    private CaseService caseService;
    @Autowired
    private CaseRepository caseRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        TestClockConfig.reset();
        jdbcTemplate.update("DELETE FROM pv_batch_item");
        jdbcTemplate.update("DELETE FROM pv_submission_batch");
        jdbcTemplate.update("DELETE FROM pv_follow_up");
        jdbcTemplate.update("DELETE FROM pv_submission");
        jdbcTemplate.update("DELETE FROM pv_case");
    }

    // ------------------------------------------------------------------
    // 7 日 / 15 日边界与迟报原因强制
    // ------------------------------------------------------------------

    @Test
    void fatalCaseSubmittedOnLastDay235959IsOnTime() {
        InitialReportResponse resp = caseService.submitInitial(initial(
                "req-fatal-ontime", Seriousness.FATAL,
                LocalDate.of(2026, 1, 10), Instant.parse("2026-01-16T23:59:59Z"), null));
        assertFalse(resp.merged());
        assertTrue(resp.onTime());
        assertEquals(LocalDate.of(2026, 1, 16), resp.caseView().initialDueDate());
        assertNull(resp.caseView().lateReason());
    }

    @Test
    void fatalCaseOneSecondLateWithoutReasonIsRejected422() {
        ApiException ex = assertThrows(ApiException.class, () -> caseService.submitInitial(initial(
                "req-fatal-late-noreason", Seriousness.FATAL,
                LocalDate.of(2026, 1, 10), Instant.parse("2026-01-17T00:00:00Z"), null)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertEquals(Integer.valueOf(0), jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pv_case", Integer.class));
    }

    @Test
    void fatalLateWithReasonIsRecordedLate() {
        InitialReportResponse resp = caseService.submitInitial(initial(
                "req-fatal-late-reason", Seriousness.LIFE_THREATENING,
                LocalDate.of(2026, 1, 10), Instant.parse("2026-01-17T00:00:00Z"),
                "春节假期报告人未在岗，获知延迟"));
        assertFalse(resp.onTime());
        assertEquals("春节假期报告人未在岗，获知延迟", resp.caseView().lateReason());
    }

    @Test
    void otherSerious15DayBoundary() {
        InitialReportRequest onTimeReq = new InitialReportRequest("req-15-ontime",
                "P-15A", "合成患者15A", "D-15", "合成药品15", "肝损伤",
                Seriousness.OTHER_SERIOUS, LocalDate.of(2026, 1, 10),
                Instant.parse("2026-01-24T23:59:59Z"), null);
        InitialReportResponse onTime = caseService.submitInitial(onTimeReq);
        assertTrue(onTime.onTime());
        assertEquals(LocalDate.of(2026, 1, 24), onTime.caseView().initialDueDate());

        InitialReportRequest lateReq = new InitialReportRequest("req-15-late",
                "P-15B", "合成患者15B", "D-15", "合成药品15", "肝损伤",
                Seriousness.OTHER_SERIOUS, LocalDate.of(2026, 1, 10),
                Instant.parse("2026-01-25T00:00:00Z"), null);
        ApiException lateNoReason = assertThrows(ApiException.class,
                () -> caseService.submitInitial(lateReq));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, lateNoReason.getStatus());
    }

    @Test
    void onTimeSubmissionWithLateReasonIsRejected() {
        ApiException ex = assertThrows(ApiException.class, () -> caseService.submitInitial(initial(
                "req-reason-not-allowed", Seriousness.FATAL,
                LocalDate.of(2026, 1, 10), Instant.parse("2026-01-12T08:00:00Z"), "误填迟报原因")));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    // ------------------------------------------------------------------
    // 重复个例并入
    // ------------------------------------------------------------------

    @Test
    void duplicateActiveCaseMergesAsFollowUpWithoutNewCase() {
        InitialReportResponse first = caseService.submitInitial(initial(
                "req-dup-1", Seriousness.FATAL,
                LocalDate.of(2026, 1, 10), Instant.parse("2026-01-12T08:00:00Z"), null));
        InitialReportResponse second = caseService.submitInitial(initial(
                "req-dup-2", Seriousness.FATAL,
                LocalDate.of(2026, 1, 15), Instant.parse("2026-01-15T09:00:00Z"), null));

        assertTrue(second.merged());
        assertEquals(first.caseNo(), second.caseNo());
        assertEquals(2, second.version());
        assertEquals(LocalDate.of(2026, 1, 15), second.caseView().latestFollowUpDate());
        assertEquals(1, second.caseView().followUpCount());
        assertEquals(Integer.valueOf(1), jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pv_case", Integer.class));
        assertEquals(Integer.valueOf(1), jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pv_follow_up", Integer.class));
    }

    // ------------------------------------------------------------------
    // 随访幂等：同键重放 / 同键异参 409
    // ------------------------------------------------------------------

    @Test
    void followUpSameKeyReplaysFirstResultAndDifferentParamsConflict409() {
        InitialReportResponse created = caseService.submitInitial(baseInitial("req-base"));
        FollowUpRequest fu = new FollowUpRequest("fu-1", LocalDate.of(2026, 1, 20),
                "实验室检查显示转氨酶升高", "RPT-001", "合成报告人甲");

        FollowUpResponse first = caseService.submitFollowUp(created.caseNo(), fu);
        assertEquals(2, first.version());

        IdempotentReplayException replay = assertThrows(IdempotentReplayException.class,
                () -> caseService.submitFollowUp(created.caseNo(), fu));
        FollowUpResponse replayed = (FollowUpResponse) replay.getReplayResponse();
        assertEquals(first.version(), replayed.version());
        assertEquals(first.caseNo(), replayed.caseNo());

        FollowUpRequest changed = new FollowUpRequest("fu-1", LocalDate.of(2026, 1, 21),
                "不同的随访内容", "RPT-001", "合成报告人甲");
        ApiException conflict = assertThrows(ApiException.class,
                () -> caseService.submitFollowUp(created.caseNo(), changed));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatus());

        assertEquals(2, caseService.getCase(created.caseNo()).version());
        assertEquals(Integer.valueOf(1), jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pv_follow_up", Integer.class));
    }

    @Test
    void multipleFollowUpsRefreshLatestDateAndVersion() {
        InitialReportResponse created = caseService.submitInitial(baseInitial("req-multi"));
        caseService.submitFollowUp(created.caseNo(), new FollowUpRequest(
                "fu-a", LocalDate.of(2026, 1, 18), "信息A", "RPT-1", "甲"));
        FollowUpResponse second = caseService.submitFollowUp(created.caseNo(), new FollowUpRequest(
                "fu-b", LocalDate.of(2026, 1, 25), "信息B", "RPT-1", "甲"));
        assertEquals(3, second.version());
        assertEquals(LocalDate.of(2026, 1, 25), second.latestFollowUpDate());
    }

    // ------------------------------------------------------------------
    // 30 日关闭边界、重复关闭幂等、关闭终态与关联新个例
    // ------------------------------------------------------------------

    @Test
    void close30DayBoundaryAndIdempotentReplay() {
        TestClockConfig.fixAt("2026-02-01T10:00:00Z");
        InitialReportResponse created = caseService.submitInitial(initial(
                "req-close-base", Seriousness.OTHER_SERIOUS,
                LocalDate.of(2026, 1, 31), Instant.parse("2026-02-01T09:00:00Z"), null));
        caseService.submitFollowUp(created.caseNo(), new FollowUpRequest(
                "fu-close", LocalDate.of(2026, 2, 1), "末次随访信息", "RPT-1", "甲"));

        TestClockConfig.fixAt("2026-03-02T10:00:00Z");
        ApiException tooEarly = assertThrows(ApiException.class,
                () -> caseService.close(created.caseNo(), null));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, tooEarly.getStatus());

        TestClockConfig.fixAt("2026-03-03T10:00:00Z");
        CloseResponse closed = caseService.close(created.caseNo(), null);
        assertEquals(CaseStatus.CLOSED.name(), closed.status());
        assertFalse(closed.idempotentReplay());

        CloseResponse again = caseService.close(created.caseNo(), null);
        assertTrue(again.idempotentReplay());
        assertEquals(CaseStatus.CLOSED.name(), again.status());
    }

    @Test
    void closedCaseRejectsFollowUpAndNewReportCreatesRelatedCase() {
        InitialReportResponse created = caseService.submitInitial(initial(
                "req-terminal", Seriousness.FATAL,
                LocalDate.of(2026, 1, 1), Instant.parse("2026-01-02T08:00:00Z"), null));
        TestClockConfig.fixAt("2026-02-02T10:00:00Z");
        caseService.close(created.caseNo(), null);

        ApiException followUpRejected = assertThrows(ApiException.class,
                () -> caseService.submitFollowUp(created.caseNo(), new FollowUpRequest(
                        "fu-after-close", LocalDate.of(2026, 2, 2), "关闭后的新信息", "RPT-1", "甲")));
        assertEquals(HttpStatus.CONFLICT, followUpRejected.getStatus());

        InitialReportResponse related = caseService.submitInitial(initial(
                "req-after-close-new", Seriousness.FATAL,
                LocalDate.of(2026, 2, 2), Instant.parse("2026-02-02T10:30:00Z"), null));
        assertNotEquals(created.caseNo(), related.caseNo());
        assertEquals(created.caseNo(), related.caseView().relatedCaseNo());
        assertEquals(CaseStatus.REPORTED, related.caseView().status());
        assertEquals(Integer.valueOf(2), jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pv_case", Integer.class));
    }

    // ------------------------------------------------------------------
    // 递交批次快照一致性与幂等
    // ------------------------------------------------------------------

    @Test
    void batchSnapshotIsFixedAndLateFollowUpDoesNotChangeIt() {
        InitialReportResponse c1 = caseService.submitInitial(initialWithPatient(
                "req-b1", "P-1", "D-1", "皮疹"));
        InitialReportResponse c2 = caseService.submitInitial(initialWithPatient(
                "req-b2", "P-2", "D-2", "发热"));

        BatchResponse batch = caseService.submitBatch(new SubmitBatchRequest(
                "batch-1", List.of(c1.caseNo(), c2.caseNo())));
        assertEquals(2, batch.caseCount());
        assertEquals(1, batch.items().get(0).version());

        caseService.submitFollowUp(c1.caseNo(), new FollowUpRequest(
                "fu-b1", LocalDate.of(2026, 1, 20), "批次提交后的迟到随访", "RPT-1", "甲"));

        BatchResponse refetched = caseService.getBatch(batch.batchNo());
        assertEquals(batch.batchNo(), refetched.batchNo());
        assertEquals(2, refetched.items().size());
        assertEquals(1, refetched.items().get(0).version());
        assertEquals(0, refetched.items().get(0).caseView().followUpCount());
        assertEquals(2, caseService.getCase(c1.caseNo()).version());

        BatchResponse replay = caseService.submitBatch(new SubmitBatchRequest(
                "batch-1", List.of(c1.caseNo(), c2.caseNo())));
        assertEquals(batch.batchNo(), replay.batchNo());

        ApiException conflict = assertThrows(ApiException.class,
                () -> caseService.submitBatch(new SubmitBatchRequest(
                        "batch-1", List.of(c1.caseNo()))));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
        assertEquals(Integer.valueOf(1), jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pv_submission_batch", Integer.class));
    }

    @Test
    void batchWithUnknownOrClosedCaseIsRejected() {
        InitialReportResponse c1 = caseService.submitInitial(baseInitial("req-bx"));
        ApiException ex = assertThrows(ApiException.class, () -> caseService.submitBatch(
                new SubmitBatchRequest("batch-x", List.of(c1.caseNo(), "PV-C-NOPE"))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    // ------------------------------------------------------------------
    // 并发边界
    // ------------------------------------------------------------------

    @Test
    void concurrentDuplicateInitialReportsProduceExactlyOneActiveCase() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<InitialReportResponse> responses = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    responses.add(caseService.submitInitial(initial(
                            "req-conc-" + idx, Seriousness.FATAL,
                            LocalDate.of(2026, 1, 10),
                            Instant.parse("2026-01-1" + (idx % 6) + "T08:00:00Z"), null)));
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "并发初报未在超时内完成");
        pool.shutdownNow();
        assertTrue(errors.isEmpty(), "并发初报不应出现错误: " + errors);
        assertEquals(threads, responses.size());
        String caseNo = responses.peek().caseNo();
        assertTrue(responses.stream().allMatch(r -> r.caseNo().equals(caseNo)));
        assertEquals(1, responses.stream().filter(r -> !r.merged()).count());
        assertEquals(threads - 1, responses.stream().filter(InitialReportResponse::merged).count());
        assertEquals(Integer.valueOf(1), jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pv_case", Integer.class));
        assertEquals(threads - 1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pv_follow_up", Integer.class));
        assertEquals(threads, caseService.getCase(caseNo).version());
    }

    @Test
    void concurrentBatchSubmitsSameRequestIdReturnSameBatch() throws Exception {
        caseService.submitInitial(initialWithPatient("req-cb1", "P-1", "D-1", "皮疹"));
        caseService.submitInitial(initialWithPatient("req-cb2", "P-2", "D-2", "发热"));

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<BatchResponse> responses = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    responses.add(caseService.submitBatch(new SubmitBatchRequest(
                            "batch-conc", null)));
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "并发递交未在超时内完成");
        pool.shutdownNow();
        assertTrue(errors.isEmpty(), "并发递交不应出现错误: " + errors);
        assertEquals(threads, responses.size());
        String batchNo = responses.peek().batchNo();
        assertTrue(responses.stream().allMatch(r -> r.batchNo().equals(batchNo)));
        assertEquals(Integer.valueOf(1), jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pv_submission_batch", Integer.class));
    }

    @Test
    void concurrentFollowUpAndBatchEndWithConsistentSnapshot() throws Exception {
        InitialReportResponse c1 = caseService.submitInitial(initialWithPatient(
                "req-race", "P-9", "D-9", "寒战"));

        int rounds = 6;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch allDone = new CountDownLatch(rounds);
        for (int i = 0; i < rounds; i++) {
            int idx = i;
            boolean followTurn = idx % 2 == 0;
            pool.submit(() -> {
                try {
                    if (followTurn) {
                        caseService.submitFollowUp(c1.caseNo(), new FollowUpRequest(
                                "fu-race-" + idx, LocalDate.of(2026, 1, 20),
                                "并发随访 " + idx, "RPT-1", "甲"));
                    } else {
                        BatchResponse b = caseService.submitBatch(
                                new SubmitBatchRequest("batch-race-" + idx, null));
                        BatchResponse again = caseService.getBatch(b.batchNo());
                        assertEquals(b.items().size(), again.items().size());
                        int snapVersion = again.items().get(0).version();
                        assertEquals(snapVersion, again.items().get(0).version());
                    }
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                } finally {
                    allDone.countDown();
                }
            });
        }
        assertTrue(allDone.await(30, TimeUnit.SECONDS), "并发随访/递交未在超时内完成");
        pool.shutdownNow();
        assertNull(error.get(), "并发随访/递交出现错误");

        List<BatchResponse> batches = jdbcTemplate.queryForList(
                        "SELECT batch_no FROM pv_submission_batch ORDER BY batch_no", String.class)
                .stream().map(caseService::getBatch).toList();
        int followUps = rounds / 2;
        assertEquals(followUps, caseService.getCase(c1.caseNo()).followUpCount());
        for (BatchResponse b : batches) {
            int snapVersion = b.items().get(0).version();
            assertEquals(snapVersion, caseService.getBatch(b.batchNo())
                    .items().get(0).version(), "批次快照应保持固定");
            assertTrue(snapVersion >= 1 && snapVersion <= followUps + 1);
        }
    }

    // ------------------------------------------------------------------
    // H2 数据库约束实体验证：活动去重唯一约束 + 关闭后释放
    // ------------------------------------------------------------------

    @Test
    void activeDedupUniqueConstraintIsEnforcedByH2AndReleasedOnClose() {
        InitialReportResponse created = caseService.submitInitial(initialWithPatient(
                "req-constraint", "P-X", "D-X", "黄疸"));

        assertThrows(DuplicateKeyException.class, () -> jdbcTemplate.update("""
                INSERT INTO pv_case (case_no, patient_code, patient_name, drug_code, drug_name,
                    reaction_event, seriousness, status, awareness_date, initial_submitted_at,
                    initial_due_date, on_time, late_reason, latest_follow_up_date, follow_up_count,
                    related_case_no, created_at, updated_at)
                VALUES ('PV-C-FORBIDDEN','P-X','患者X','D-X','药X','黄疸','FATAL','REPORTED',
                    DATE '2026-01-10', TIMESTAMP '2026-01-12 08:00:00',
                    DATE '2026-01-16', TRUE, NULL, NULL, 0, NULL,
                    TIMESTAMP '2026-01-12 08:00:00', TIMESTAMP '2026-01-12 08:00:00')
                """));

        TestClockConfig.fixAt("2026-02-20T10:00:00Z");
        caseService.close(created.caseNo(), null);
        jdbcTemplate.update("""
                INSERT INTO pv_case (case_no, patient_code, patient_name, drug_code, drug_name,
                    reaction_event, seriousness, status, awareness_date, initial_submitted_at,
                    initial_due_date, on_time, late_reason, latest_follow_up_date, follow_up_count,
                    related_case_no, created_at, updated_at)
                VALUES ('PV-C-ALLOWED','P-X','患者X','D-X','药X','黄疸','FATAL','REPORTED',
                    DATE '2026-02-20', TIMESTAMP '2026-02-20 10:00:00',
                    DATE '2026-02-26', TRUE, NULL, NULL, 0, ?,
                    TIMESTAMP '2026-02-20 10:00:00', TIMESTAMP '2026-02-20 10:00:00')
                """, created.caseNo());
        List<CasePo> cases = caseRepository.findAll();
        assertEquals(2, cases.size());
    }

    // ------------------------------------------------------------------
    // 测试辅助构造
    // ------------------------------------------------------------------

    private InitialReportRequest baseInitial(String requestId) {
        return initial(requestId, Seriousness.FATAL,
                LocalDate.of(2026, 1, 10), Instant.parse("2026-01-12T08:00:00Z"), null);
    }

    private InitialReportRequest initial(String requestId, Seriousness seriousness,
                                         LocalDate awarenessDate, Instant submittedAt,
                                         String lateReason) {
        return new InitialReportRequest(requestId, "P-001", "合成患者张三", "D-001",
                "合成药品A", "过敏性休克", seriousness, awarenessDate, submittedAt, lateReason);
    }

    private InitialReportRequest initialWithPatient(String requestId, String patientCode,
                                                    String drugCode, String reaction) {
        return new InitialReportRequest(requestId, patientCode, "合成患者", drugCode,
                "合成药品" + drugCode, reaction, Seriousness.FATAL,
                LocalDate.of(2026, 1, 10), Instant.parse("2026-01-12T08:00:00Z"), null);
    }
}
