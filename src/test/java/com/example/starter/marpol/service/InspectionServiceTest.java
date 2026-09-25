package com.example.starter.marpol.service;

import com.example.starter.marpol.AbstractIntegrationTest;
import com.example.starter.marpol.TestFixtures;
import com.example.starter.marpol.domain.Decision;
import com.example.starter.marpol.domain.InspectionState;
import com.example.starter.marpol.domain.ItemStatus;
import com.example.starter.marpol.domain.RecordVersionStatus;
import com.example.starter.marpol.error.ConflictException;
import com.example.starter.marpol.model.EmissionRecord;
import com.example.starter.marpol.model.Inspection;
import com.example.starter.marpol.model.InspectionItem;
import com.example.starter.marpol.model.InspectionReview;
import com.example.starter.marpol.model.Ship;
import com.example.starter.marpol.model.Voyage;
import com.example.starter.marpol.web.CorrectRecordRequest;
import com.example.starter.marpol.web.DecisionRequest;
import com.example.starter.marpol.web.ReviewRequest;
import com.example.starter.marpol.web.SubmitRecordRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核查服务集成测试：逐条结论、签发终态幂等、整改期限（恰第 14 日）、复查、并发口径。
 */
class InspectionServiceTest extends AbstractIntegrationTest {

    @Autowired
    private RecordService recordService;
    @Autowired
    private InspectionService inspectionService;
    @Autowired
    private TestFixtures fixtures;

    private Ship ship;
    private Voyage voyage;
    private final Instant eventTime = Instant.parse("2026-09-25T08:00:00Z");

    @BeforeEach
    void setup() {
        ship = fixtures.ship("IMO-2000002");
        voyage = fixtures.voyage(ship.imo(), "V-9");
        // 固定核查日：2026-09-25 UTC，整改截止日应为 2026-10-09（恰 +14 自然日）。
        clock.setClock(Clock.fixed(Instant.parse("2026-09-25T10:00:00Z"), ZoneOffset.UTC));
    }

    private EmissionRecord submitRecord(String requestId, double lon) {
        return recordService.submit(new SubmitRecordRequest(requestId, ship.imo(), voyage.voyageNo(),
                "OIL", lon, 20, eventTime, new BigDecimal("1.000"), "TON"));
    }

    @Test
    void violationCreatesDeficiencyWithDeadlineInspectionDatePlus14() {
        EmissionRecord r = submitRecord("REQ-INSP-1", 20);
        Inspection inspection = inspectionService.start(ship.imo(), "OFFICER-A");

        InspectionItem item = inspectionService.decide(inspection.inspectionNo(),
                new DecisionRequest(r.id(), "VIOLATION", "疑似油类超标"));

        assertEquals(Decision.VIOLATION, item.decision());
        assertEquals(ItemStatus.OPEN, item.status());
        assertEquals(LocalDate.of(2026, 9, 25), inspection.inspectionDate());
        assertEquals(LocalDate.of(2026, 10, 9), item.dueDate());
        assertEquals(r.id(), item.pinnedRecordId());
        assertEquals(1, item.pinnedVersionNo());
    }

    @Test
    void confirmedItemHasNoDeficiencyDeadline() {
        EmissionRecord r = submitRecord("REQ-INSP-2", 21);
        Inspection inspection = inspectionService.start(ship.imo(), "OFFICER-A");
        InspectionItem item = inspectionService.decide(inspection.inspectionNo(),
                new DecisionRequest(r.id(), "CONFIRMED", null));
        assertEquals(Decision.CONFIRMED, item.decision());
        assertNull(item.status());
        assertNull(item.dueDate());
    }

    @Test
    void rectifyExactlyOnDay14IsValid() {
        EmissionRecord r = submitRecord("REQ-INSP-3", 22);
        Inspection inspection = inspectionService.start(ship.imo(), "OFFICER-A");
        InspectionItem item = inspectionService.decide(inspection.inspectionNo(),
                new DecisionRequest(r.id(), "VIOLATION", "x"));

        // 恰第 14 个自然日（UTC 日界）关闭，有效。
        clock.setClock(Clock.fixed(Instant.parse("2026-10-09T23:59:59Z"), ZoneOffset.UTC));
        InspectionItem rectified = inspectionService.rectify(inspection.inspectionNo(), item.id());
        assertEquals(ItemStatus.RECTIFIED, rectified.status());
        assertNotNull(rectified.rectifiedAt());
    }

    @Test
    void rectifyOnDay15IsOverdueAndRejected() {
        EmissionRecord r = submitRecord("REQ-INSP-4", 23);
        Inspection inspection = inspectionService.start(ship.imo(), "OFFICER-A");
        InspectionItem item = inspectionService.decide(inspection.inspectionNo(),
                new DecisionRequest(r.id(), "VIOLATION", "x"));

        clock.setClock(Clock.fixed(Instant.parse("2026-10-10T00:00:01Z"), ZoneOffset.UTC));
        assertThrows(ConflictException.class,
                () -> inspectionService.rectify(inspection.inspectionNo(), item.id()));
        // 已被标记逾期。
        String status = jdbc.queryForObject(
                "SELECT status FROM inspection_item WHERE id = ?", String.class, item.id());
        assertEquals("OVERDUE", status);
    }

    @Test
    void issueIsTerminalAndIdempotentAndRejectsDecisionChanges() {
        EmissionRecord r = submitRecord("REQ-INSP-5", 24);
        Inspection inspection = inspectionService.start(ship.imo(), "OFFICER-A");
        inspectionService.decide(inspection.inspectionNo(),
                new DecisionRequest(r.id(), "CONFIRMED", null));

        Inspection issued1 = inspectionService.issue(inspection.inspectionNo());
        Inspection issued2 = inspectionService.issue(inspection.inspectionNo());
        assertEquals(InspectionState.ISSUED, issued1.status());
        assertEquals(issued1.issuedAt(), issued2.issuedAt());

        // 签发后修改结论一律拒绝。
        assertThrows(ConflictException.class, () -> inspectionService.decide(inspection.inspectionNo(),
                new DecisionRequest(r.id(), "VIOLATION", "翻案")));
    }

    @Test
    void reviewAfterIssueReferencesOriginalInspectionAndKeepsConclusion() {
        EmissionRecord r = submitRecord("REQ-INSP-6", 25);
        Inspection inspection = inspectionService.start(ship.imo(), "OFFICER-A");
        inspectionService.decide(inspection.inspectionNo(),
                new DecisionRequest(r.id(), "VIOLATION", "初判"));
        inspectionService.issue(inspection.inspectionNo());

        InspectionReview review = inspectionService.review(new ReviewRequest(
                inspection.inspectionNo(), null, "补充实验室检测报告"));
        assertEquals(inspection.inspectionNo(), review.inspectionNo());
        assertEquals("补充实验室检测报告", review.evidence());

        // 原结论保持不变。
        var view = inspectionService.toResponse(inspectionService.requireByNo(inspection.inspectionNo()));
        assertEquals(1, view.reviews().size());
        assertEquals("VIOLATION", view.items().get(0).decision());
    }

    @Test
    void reviewBeforeIssueIsRejected() {
        Inspection inspection = inspectionService.start(ship.imo(), "OFFICER-A");
        assertThrows(ConflictException.class, () -> inspectionService.review(
                new ReviewRequest(inspection.inspectionNo(), null, "提前补充")));
    }

    @Test
    void lateCorrectionAfterDecisionDoesNotChangePinnedVersion() {
        EmissionRecord v1 = submitRecord("REQ-INSP-7", 26);
        Inspection inspection = inspectionService.start(ship.imo(), "OFFICER-A");
        InspectionItem item = inspectionService.decide(inspection.inspectionNo(),
                new DecisionRequest(v1.id(), "CONFIRMED", null));
        inspectionService.issue(inspection.inspectionNo());

        // 迟到修正：产生新版本，但已签发核查钉住的仍是 v1。
        EmissionRecord v2 = recordService.correct(new CorrectRecordRequest(
                v1.id(), "REQ-INSP-7-C", "OIL", 27, 20, eventTime, new BigDecimal("1.100"), "TON"));
        assertEquals(2, v2.versionNo());

        var view = inspectionService.toResponse(inspectionService.requireByNo(inspection.inspectionNo()));
        assertEquals(1, view.items().get(0).pinnedVersionNo());
        assertEquals(v1.id(), view.items().get(0).pinnedRecordId());
    }

    @Test
    void concurrentDecisionAndCorrectionPinOneConsistentVersion() throws Exception {
        EmissionRecord v1 = submitRecord("REQ-INSP-8", 28);
        Inspection inspection = inspectionService.start(ship.imo(), "OFFICER-A");

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<InspectionItem> decisionFuture = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return inspectionService.decide(inspection.inspectionNo(),
                        new DecisionRequest(v1.id(), "VIOLATION", "并发标注"));
            });
            Future<EmissionRecord> correctionFuture = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return recordService.correct(new CorrectRecordRequest(
                        v1.id(), "REQ-INSP-8-C", "OIL", 29, 20, eventTime,
                        new BigDecimal("1.200"), "TON"));
            });

            InspectionItem item = decisionFuture.get(15, TimeUnit.SECONDS);
            EmissionRecord corrected = correctionFuture.get(15, TimeUnit.SECONDS);

            // 修正必然产生 v2；结论钉住的版本号必然是真实存在的版本（1 或 2），
            // 且签发后不再漂移。
            assertEquals(2, corrected.versionNo());
            assertTrue(Set.of(1, 2).contains(item.pinnedVersionNo()));
            inspectionService.issue(inspection.inspectionNo());
            var view = inspectionService.toResponse(
                    inspectionService.requireByNo(inspection.inspectionNo()));
            assertEquals(item.pinnedVersionNo(), view.items().get(0).pinnedVersionNo());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentCorrectionsOnlyOneVersionWins() throws Exception {
        EmissionRecord v1 = submitRecord("REQ-INSP-9", 30);

        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    recordService.correct(new CorrectRecordRequest(
                            v1.id(), "REQ-INSP-9-C" + idx, "OIL", 31 + idx, 20, eventTime,
                            new BigDecimal("1." + idx), "TON"));
                    return null;
                }));
            }
            int success = 0;
            int conflict = 0;
            for (Future<?> f : futures) {
                try {
                    f.get(20, TimeUnit.SECONDS);
                    success++;
                } catch (Exception e) {
                    conflict++;
                }
            }
            assertEquals(1, success, "并发修正只允许一个版本胜出");
            assertEquals(threads - 1, conflict, "失败者必须返回冲突");

            // 最终只有 v1、v2 两个版本，最新为 ACTIVE。
            EmissionRecord latest = recordService.get(v1.id(), null);
            assertEquals(2, latest.versionNo());
            assertEquals(RecordVersionStatus.ACTIVE, latest.status());
            Integer versions = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM emission_record WHERE root_id = ?", Integer.class, v1.id());
            assertEquals(2, versions);
        } finally {
            pool.shutdownNow();
        }
    }
}
