package com.example.starter.pv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.MutableClock;
import com.example.starter.TestClockConfig;
import com.example.starter.pv.dto.CloseCaseResponse;
import com.example.starter.pv.dto.FollowUpRequest;
import com.example.starter.pv.dto.InitialReportRequest;
import com.example.starter.pv.dto.ReportResponse;
import com.example.starter.pv.dto.SubmissionBatchRequest;
import com.example.starter.pv.dto.SubmissionBatchResponse;

/**
 * 个例服务集成测试：真实 H2（MySQL 兼容模式）验证时限边界、重复并入、幂等、
 * 关闭终态、递交批次快照与并发边界。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class PvCaseServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final LocalDate AWARENESS = LocalDate.of(2026, 1, 1);

    @Autowired
    private PvCaseService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM pv_submission_batch_item");
        jdbc.update("DELETE FROM pv_submission_batch");
        jdbc.update("DELETE FROM pv_report");
        jdbc.update("DELETE FROM pv_case");
        clock.setInstant(T0);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM pv_submission_batch_item");
        jdbc.update("DELETE FROM pv_submission_batch");
        jdbc.update("DELETE FROM pv_report");
        jdbc.update("DELETE FROM pv_case");
    }

    private InitialReportRequest initial(String requestId, String patient, String drug, String event,
                                         Seriousness seriousness, Instant submittedAt, String lateReason) {
        return new InitialReportRequest(requestId, patient, drug, event, seriousness, AWARENESS,
                "narrative-" + requestId, lateReason, submittedAt);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    // ---------- 7 日 / 15 日时限边界与迟报原因强制 ----------

    @Test
    void fatalCase_timelyAtLastSecondOfDay7() {
        // 致死个例：T0+7 日 23:59:59 提交判为及时
        Instant lastMoment = Instant.parse("2026-01-08T23:59:59Z");
        ReportResponse r = service.submitInitialReport(
                initial("req-f1", "P001", "DRUG-A", "EVT-X", Seriousness.FATAL_OR_LIFE_THREATENING,
                        lastMoment, null));
        assertFalse(r.late());
        assertNull(r.lateReason());
        assertEquals(Instant.parse("2026-01-08T23:59:59.999999999Z"), r.dueAt());
        assertEquals(ReportType.INITIAL, r.reportType());
        assertFalse(r.mergedAsFollowUp());
    }

    @Test
    void fatalCase_lateAfterDeadline_requiresLateReason() {
        // T0+8 日 00:00:00 提交判为迟报
        Instant tooLate = Instant.parse("2026-01-09T00:00:00Z");
        ApiException e = assertThrows(ApiException.class, () -> service.submitInitialReport(
                initial("req-f2", "P001", "DRUG-A", "EVT-X", Seriousness.FATAL_OR_LIFE_THREATENING,
                        tooLate, null)));
        assertEquals(HttpStatus.BAD_REQUEST, e.status());
        assertEquals(0, count("pv_case"));
        assertEquals(0, count("pv_report"));

        ReportResponse r = service.submitInitialReport(
                initial("req-f2", "P001", "DRUG-A", "EVT-X", Seriousness.FATAL_OR_LIFE_THREATENING,
                        tooLate, "医院节假日延迟上报"));
        assertTrue(r.late());
        assertEquals("医院节假日延迟上报", r.lateReason());
    }

    @Test
    void otherSerious_timelyAtLastSecondOfDay15_lateAtDay16() {
        Instant lastMoment = Instant.parse("2026-01-16T23:59:59Z");
        ReportResponse timely = service.submitInitialReport(
                initial("req-s1", "P002", "DRUG-B", "EVT-Y", Seriousness.OTHER_SERIOUS,
                        lastMoment, null));
        assertFalse(timely.late());
        assertEquals(Instant.parse("2026-01-16T23:59:59.999999999Z"), timely.dueAt());

        Instant tooLate = Instant.parse("2026-01-17T00:00:00Z");
        ReportResponse late = service.submitInitialReport(
                initial("req-s2", "P003", "DRUG-B", "EVT-Z", Seriousness.OTHER_SERIOUS,
                        tooLate, "漏报后补报"));
        assertTrue(late.late());
    }

    // ---------- 重复个例并入 ----------

    @Test
    void duplicateReport_mergedAsFollowUp_noNewCase() {
        ReportResponse first = service.submitInitialReport(
                initial("req-d1", "P010", "DRUG-C", "EVT-M", Seriousness.OTHER_SERIOUS,
                        T0, null));
        ReportResponse merged = service.submitInitialReport(
                initial("req-d2", "P010", "DRUG-C", "EVT-M", Seriousness.OTHER_SERIOUS,
                        T0.plusSeconds(3600), null));

        assertEquals(first.caseNo(), merged.caseNo());
        assertTrue(merged.mergedAsFollowUp());
        assertEquals(ReportType.FOLLOW_UP, merged.reportType());
        assertEquals(2, merged.caseVersion());
        assertEquals(1, count("pv_case"));
        assertEquals(2, count("pv_report"));

        var view = service.getCase(first.caseNo());
        assertEquals(CaseStatus.FOLLOW_UP_SUBMITTED, view.status());
        assertEquals(T0.plusSeconds(3600), view.lastFollowUpAt());
    }

    // ---------- 幂等：同键重放与同键异参 ----------

    @Test
    void idempotentReplay_sameKeySameParams_returnsFirstResult() {
        InitialReportRequest req = initial("req-i1", "P020", "DRUG-D", "EVT-N",
                Seriousness.OTHER_SERIOUS, T0, null);
        ReportResponse first = service.submitInitialReport(req);
        ReportResponse replay = service.submitInitialReport(req);

        assertEquals(first.reportId(), replay.reportId());
        assertEquals(first.caseNo(), replay.caseNo());
        assertEquals(1, replay.caseVersion());
        assertEquals(1, count("pv_report"));
        assertEquals(1, count("pv_case"));
    }

    @Test
    void idempotentConflict_sameKeyDifferentParams_409_noNewVersion() {
        service.submitInitialReport(initial("req-i2", "P021", "DRUG-D", "EVT-N",
                Seriousness.OTHER_SERIOUS, T0, null));
        InitialReportRequest tampered = new InitialReportRequest("req-i2", "P021", "DRUG-D", "EVT-N",
                Seriousness.OTHER_SERIOUS, AWARENESS, "不同的叙述", null, T0);

        ApiException e = assertThrows(ApiException.class, () -> service.submitInitialReport(tampered));
        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals(1, count("pv_report"));
        var view = service.getCase(service.submitInitialReport(
                initial("req-i3", "P022", "DRUG-D", "EVT-N", Seriousness.OTHER_SERIOUS, T0, null))
                .caseNo());
        assertEquals(1, view.version());
    }

    @Test
    void followUp_idempotentReplayAndConflict() {
        ReportResponse initial = service.submitInitialReport(
                initial("req-i4", "P023", "DRUG-E", "EVT-P", Seriousness.OTHER_SERIOUS, T0, null));
        FollowUpRequest fu = new FollowUpRequest("req-fu1", "补充实验室检查", T0.plusSeconds(7200));
        ReportResponse first = service.submitFollowUp(initial.caseNo(), fu);
        ReportResponse replay = service.submitFollowUp(initial.caseNo(), fu);
        assertEquals(first.reportId(), replay.reportId());
        assertEquals(2, replay.caseVersion());

        ApiException e = assertThrows(ApiException.class, () -> service.submitFollowUp(initial.caseNo(),
                new FollowUpRequest("req-fu1", "被篡改的随访内容", T0.plusSeconds(7200))));
        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals(2, service.getCase(initial.caseNo()).version());
        assertEquals(2, count("pv_report"));
    }

    // ---------- 30 日关闭边界与关闭终态 ----------

    @Test
    void close_exactly30DaysAfterLastFollowUp_allowed_29Days_rejected() {
        ReportResponse initial = service.submitInitialReport(
                initial("req-c1", "P030", "DRUG-F", "EVT-Q", Seriousness.OTHER_SERIOUS, T0, null));
        service.submitFollowUp(initial.caseNo(),
                new FollowUpRequest("req-c1-fu", "随访一", T0.plus(Duration.ofDays(10))));

        // 基准为最近随访日 T0+10：第 39 天（距随访 29 天）不可关闭
        clock.setInstant(T0.plus(Duration.ofDays(39)));
        ApiException e = assertThrows(ApiException.class,
                () -> service.closeCase(initial.caseNo()));
        assertEquals(HttpStatus.CONFLICT, e.status());

        // 恰满 30 日可申请
        clock.setInstant(T0.plus(Duration.ofDays(40)));
        CloseCaseResponse closed = service.closeCase(initial.caseNo());
        assertEquals(CaseStatus.CLOSED, closed.status());
        assertEquals(T0.plus(Duration.ofDays(40)), closed.closedAt());
    }

    @Test
    void close_withoutFollowUp_baselineIsInitialSubmission() {
        ReportResponse initial = service.submitInitialReport(
                initial("req-c2", "P031", "DRUG-F", "EVT-R", Seriousness.OTHER_SERIOUS, T0, null));
        clock.setInstant(T0.plus(Duration.ofDays(30)).minusSeconds(1));
        assertThrows(ApiException.class, () -> service.closeCase(initial.caseNo()));

        clock.setInstant(T0.plus(Duration.ofDays(30)));
        CloseCaseResponse closed = service.closeCase(initial.caseNo());
        assertEquals(CaseStatus.CLOSED, closed.status());
    }

    @Test
    void closedCase_terminalState_repeatCloseIdempotent_followUpRejected_newLinkedCase() {
        ReportResponse initial = service.submitInitialReport(
                initial("req-c3", "P032", "DRUG-G", "EVT-S", Seriousness.OTHER_SERIOUS, T0, null));
        clock.setInstant(T0.plus(Duration.ofDays(30)));
        CloseCaseResponse first = service.closeCase(initial.caseNo());

        // 重复关闭：幂等成功，返回首次结果
        clock.setInstant(T0.plus(Duration.ofDays(35)));
        CloseCaseResponse again = service.closeCase(initial.caseNo());
        assertEquals(first.closedAt(), again.closedAt());
        assertEquals(first.caseVersion(), again.caseVersion());

        // 关闭后随访不得重开
        ApiException e = assertThrows(ApiException.class, () -> service.submitFollowUp(
                initial.caseNo(), new FollowUpRequest("req-c3-fu", "迟到的随访", null)));
        assertEquals(HttpStatus.CONFLICT, e.status());

        // 新信息须新建关联个例并引用原编号（提交时刻晚于原获知日期限，属迟报，须填迟报原因）
        ReportResponse linked = service.submitInitialReport(
                initial("req-c4", "P032", "DRUG-G", "EVT-S", Seriousness.OTHER_SERIOUS,
                        T0.plus(Duration.ofDays(35)), "关闭后新信息补报"));
        assertNotEquals(initial.caseNo(), linked.caseNo());
        assertEquals(ReportType.INITIAL, linked.reportType());
        assertFalse(linked.mergedAsFollowUp());
        assertEquals(initial.caseNo(), service.getCase(linked.caseNo()).linkedCaseNo());
        assertEquals(CaseStatus.CLOSED, service.getCase(initial.caseNo()).status());
        assertEquals(2, count("pv_case"));
    }

    // ---------- 递交批次：快照固定、幂等重放 ----------

    @Test
    void submissionBatch_snapshotFixedAndIdempotentReplay() {
        ReportResponse caseA = service.submitInitialReport(
                initial("req-b1", "P040", "DRUG-H", "EVT-T", Seriousness.OTHER_SERIOUS, T0, null));
        ReportResponse caseB = service.submitInitialReport(
                initial("req-b2", "P041", "DRUG-I", "EVT-U", Seriousness.OTHER_SERIOUS, T0, null));

        SubmissionBatchResponse batch = service.createSubmissionBatch(
                new SubmissionBatchRequest("req-batch-1"));
        assertTrue(batch.batchNo().startsWith("PB-"));
        assertEquals(2, batch.items().size());

        // 批次生成后的随访不影响已固定批次
        service.submitFollowUp(caseA.caseNo(),
                new FollowUpRequest("req-b1-fu", "批次后的随访", T0.plusSeconds(3600)));
        SubmissionBatchResponse reloaded = service.getBatch(batch.batchNo());
        assertEquals(batch.items(), reloaded.items());
        assertEquals(1, reloaded.items().stream()
                .filter(i -> i.caseNo().equals(caseA.caseNo())).findFirst().orElseThrow().caseVersion());

        // 同 requestId 重放返回首次批次
        SubmissionBatchResponse replay = service.createSubmissionBatch(
                new SubmissionBatchRequest("req-batch-1"));
        assertEquals(batch.batchNo(), replay.batchNo());
        assertEquals(batch.items(), replay.items());
        assertEquals(1, count("pv_submission_batch"));

        // 新批次反映最新状态，且批次号唯一
        SubmissionBatchResponse second = service.createSubmissionBatch(
                new SubmissionBatchRequest("req-batch-2"));
        assertNotEquals(batch.batchNo(), second.batchNo());
        assertEquals(2, second.items().stream()
                .filter(i -> i.caseNo().equals(caseA.caseNo())).findFirst().orElseThrow().caseVersion());

        // 已关闭个例不进入新批次
        clock.setInstant(T0.plus(Duration.ofDays(31)));
        service.closeCase(caseB.caseNo());
        SubmissionBatchResponse third = service.createSubmissionBatch(
                new SubmissionBatchRequest("req-batch-3"));
        assertEquals(1, third.items().size());
        assertEquals(caseA.caseNo(), third.items().get(0).caseNo());
    }

    // ---------- 并发边界 ----------

    @Test
    void concurrentInitialReports_sameTriple_singleActiveCase() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ReportResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "req-conc-" + i;
            futures.add(pool.submit(() -> {
                assertTrue(gate.await(5, TimeUnit.SECONDS));
                return service.submitInitialReport(initial(requestId, "P050", "DRUG-J", "EVT-V",
                        Seriousness.OTHER_SERIOUS, T0, null));
            }));
        }
        gate.countDown();
        Set<String> caseNos = new HashSet<>();
        Set<Long> reportIds = new HashSet<>();
        for (Future<ReportResponse> f : futures) {
            ReportResponse r = f.get(15, TimeUnit.SECONDS);
            caseNos.add(r.caseNo());
            reportIds.add(r.reportId());
        }
        pool.shutdown();

        assertEquals(1, caseNos.size());
        assertEquals(threads, reportIds.size());
        assertEquals(1, count("pv_case"));
        assertEquals(threads, count("pv_report"));
        // 1 次初报 + 7 次并入随访，版本递增至 8
        assertEquals(threads, service.getCase(caseNos.iterator().next()).version());
    }

    @Test
    void concurrentSameRequestId_singleReport_singleVersion() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ReportResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                assertTrue(gate.await(5, TimeUnit.SECONDS));
                return service.submitInitialReport(initial("req-same", "P051", "DRUG-K", "EVT-W",
                        Seriousness.OTHER_SERIOUS, T0, null));
            }));
        }
        gate.countDown();
        Set<Long> reportIds = new HashSet<>();
        Set<String> caseNos = new HashSet<>();
        for (Future<ReportResponse> f : futures) {
            ReportResponse r = f.get(15, TimeUnit.SECONDS);
            reportIds.add(r.reportId());
            caseNos.add(r.caseNo());
        }
        pool.shutdown();

        assertEquals(1, reportIds.size());
        assertEquals(1, caseNos.size());
        assertEquals(1, count("pv_report"));
        assertEquals(1, count("pv_case"));
        assertEquals(1, service.getCase(caseNos.iterator().next()).version());
    }

    @Test
    void submissionBatch_concurrentWithFollowUp_snapshotIsCommittedStateAtSubmission() throws Exception {
        ReportResponse initial = service.submitInitialReport(
                initial("req-l1", "P060", "DRUG-L", "EVT-X1", Seriousness.OTHER_SERIOUS, T0, null));
        Instant followUpAt = T0.plusSeconds(7200);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // 线程 1：行锁持有个例，在事务内提交随访后释放
        Future<?> locker = pool.submit(() -> tx.execute(status -> {
            jdbc.queryForObject("SELECT id FROM pv_case WHERE case_no = ? FOR UPDATE",
                    Long.class, initial.caseNo());
            locked.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            service.submitFollowUp(initial.caseNo(),
                    new FollowUpRequest("req-l1-fu", "锁内随访", followUpAt));
            return null;
        }));

        assertTrue(locked.await(10, TimeUnit.SECONDS));
        // 线程 2：行锁持有期间创建批次，须等待锁释放后读取已确认的随访快照
        Future<SubmissionBatchResponse> batchFuture = pool.submit(() ->
                service.createSubmissionBatch(new SubmissionBatchRequest("req-batch-lock")));
        release.countDown();

        SubmissionBatchResponse batch = batchFuture.get(15, TimeUnit.SECONDS);
        locker.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, batch.items().size());
        SubmissionBatchResponse.Item item = batch.items().get(0);
        assertEquals(initial.caseNo(), item.caseNo());
        assertEquals(2, item.caseVersion());
        assertEquals(followUpAt, item.lastFollowUpAt());
        // 批次内容稳定：重复查询一致
        assertEquals(batch.items(), service.getBatch(batch.batchNo()).items());
    }
}
