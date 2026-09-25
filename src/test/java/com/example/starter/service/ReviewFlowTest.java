package com.example.starter.service;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.catalog.DocumentCatalog;
import com.example.starter.domain.ReviewStatus;
import com.example.starter.dto.CorrectRequest;
import com.example.starter.dto.IssueRequest;
import com.example.starter.dto.ReviewView;
import com.example.starter.dto.ServiceResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主流程测试：申报缺项告知 → 恰第 5 个工作日补正 → 检验核对 → 综合评定 → 公示 → 签发归档。
 */
class ReviewFlowTest extends AbstractIntegrationTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void missingDocumentsAreNotifiedOnceAndAcceptedAfterTimelyCorrection() {
        // 周一告知
        clock.setFixed(LocalDate.of(2026, 9, 7).atTime(10, 0).atZone(SHANGHAI).toInstant());

        List<String> submitted = new ArrayList<>(DocumentCatalog.requiredDocuments());
        String removedA = DocumentCatalog.STABILITY_REPORT;
        String removedB = DocumentCatalog.LABEL_INSERT;
        submitted.remove(removedA);
        submitted.remove(removedB);

        ServiceResult declared = reviewService.declare(
                declareRequest("req-declare-1", "B-FLOW-1", submitted, boundaryPassingInspections()));
        assertEquals(200, declared.httpStatus());
        ReviewView view = asView(declared);
        assertEquals(ReviewStatus.PENDING_CORRECTION.name(), view.status());
        // 一次性告知全部缺项（2 项），而不是每次只告知一项
        assertEquals(List.of(removedA, removedB), view.missingDocuments());
        assertEquals(LocalDate.of(2026, 9, 7), view.correctionNoticeDate());
        assertEquals(LocalDate.of(2026, 9, 14), view.correctionDeadline());

        // 恰第 5 个工作日（2026-09-14 周一）当日提交，受理
        clock.setFixed(LocalDate.of(2026, 9, 14).atTime(16, 0).atZone(SHANGHAI).toInstant());
        ServiceResult corrected = reviewService.correct("B-FLOW-1",
                new CorrectRequest("req-correct-1", List.of(removedA, removedB)));
        assertEquals(200, corrected.httpStatus());
        ReviewView correctedView = asView(corrected);
        assertEquals(ReviewStatus.INSPECTION_CHECKED.name(), correctedView.status());
        assertTrue(correctedView.missingDocuments().isEmpty());
        assertNotNull(correctedView.correctedAt());
        assertEquals(DocumentCatalog.requiredDocuments().size(), correctedView.documents().size());
    }

    @Test
    void correctionAfterDeadlineIsWithdrawn() {
        clock.setFixed(LocalDate.of(2026, 9, 7).atTime(10, 0).atZone(SHANGHAI).toInstant());
        List<String> docs = new ArrayList<>(allDocuments());
        docs.remove(DocumentCatalog.LABEL_INSERT);

        reviewService.declare(declareRequest("req-d-2", "B-FLOW-2", docs, boundaryPassingInspections()));

        // 截止日次日（2026-09-15）补正：逾期按撤回
        clock.setFixed(LocalDate.of(2026, 9, 15).atTime(9, 0).atZone(SHANGHAI).toInstant());
        ServiceResult late = reviewService.correct("B-FLOW-2",
                new CorrectRequest("req-c-2", List.of(DocumentCatalog.LABEL_INSERT)));
        assertEquals(400, late.httpStatus());
        assertEquals(ReviewStatus.WITHDRAWN.name(), reviewService.getReview("B-FLOW-2").status());
    }

    @Test
    void correctionStillMissingDocumentsIsRejectedWithoutStateChange() {
        clock.setFixed(LocalDate.of(2026, 9, 7).atTime(10, 0).atZone(SHANGHAI).toInstant());
        List<String> docs = new ArrayList<>(allDocuments());
        docs.remove(DocumentCatalog.STABILITY_REPORT);
        docs.remove(DocumentCatalog.LABEL_INSERT);

        reviewService.declare(declareRequest("req-d-3", "B-FLOW-3", docs, boundaryPassingInspections()));

        clock.setFixed(LocalDate.of(2026, 9, 8).atTime(10, 0).atZone(SHANGHAI).toInstant());
        ServiceResult partial = reviewService.correct("B-FLOW-3",
                new CorrectRequest("req-c-3", List.of(DocumentCatalog.STABILITY_REPORT)));
        assertEquals(400, partial.httpStatus());
        ReviewView view = reviewService.getReview("B-FLOW-3");
        assertEquals(ReviewStatus.PENDING_CORRECTION.name(), view.status());
        assertEquals(List.of(DocumentCatalog.STABILITY_REPORT, DocumentCatalog.LABEL_INSERT),
                view.missingDocuments(), "缺项仍为一次性告知的原始缺项列表");
        assertNull(view.correctedAt());
    }

    @Test
    void criticalItemsExactlyAtLimitsPassAndFlowGoesThroughToIssue() {
        // 2026-09-07 周一申报，资料齐全；效力恰 4.0、异常毒性恰 0.10、无菌
        Instant declaredInstant = LocalDate.of(2026, 9, 7).atTime(10, 0).atZone(SHANGHAI).toInstant();
        clock.setFixed(declaredInstant);
        ServiceResult declared = reviewService.declare(
                declareRequest("req-d-4", "B-FLOW-4", allDocuments(), boundaryPassingInspections()));
        assertEquals(200, declared.httpStatus());
        ReviewView inspected = asView(declared);
        assertEquals(ReviewStatus.INSPECTION_CHECKED.name(), inspected.status());
        assertTrue(inspected.inspections().stream().allMatch(i -> i.qualified()),
                "恰等限值的关键项目必须全部复算合格");

        // 综合评定通过并启动公示
        ServiceResult evaluated = reviewService.evaluate("B-FLOW-4");
        assertEquals(200, evaluated.httpStatus());
        ReviewView publicizing = asView(evaluated);
        assertEquals(ReviewStatus.PUBLICIZING.name(), publicizing.status());
        assertEquals("PASS", publicizing.conclusion());
        assertEquals(LocalDate.of(2026, 9, 7), publicizing.publicityStartUtc(),
                "Shanghai 2026-09-07 10:00 对应 UTC 2026-09-07 02:00");
        assertEquals(LocalDate.of(2026, 9, 14), publicizing.publicityEndUtc(),
                "公示恰第 7 个自然日期满");
        assertEquals("PUBLICIZING", publicizing.publicity().status());

        // 恰第 7 日（UTC 日界刚过）期满生效
        clock.setFixed(LocalDate.of(2026, 9, 14).atStartOfDay(ZoneId.of("UTC")).toInstant());
        ServiceResult settled = reviewService.settlePublicity("B-FLOW-4");
        assertEquals(200, settled.httpStatus());
        assertEquals(ReviewStatus.PUBLICITY_EFFECTIVE.name(), asView(settled).status());
        assertEquals("EFFECTIVE", asView(settled).publicity().status());

        // 签发归档为终态
        ServiceResult issued = reviewService.issue("B-FLOW-4", new IssueRequest("req-i-4"));
        assertEquals(200, issued.httpStatus());
        ReviewView issuedView = asView(issued);
        assertEquals(ReviewStatus.ISSUED.name(), issuedView.status());
        assertNotNull(issuedView.issuedAt());
    }

    @Test
    void publicityBeforeSeventhDayCannotSettleAndSettlementIsIdempotent() {
        clock.setFixed(Instant.parse("2026-09-07T02:00:00Z"));
        reviewService.declare(declareRequest("req-d-5", "B-FLOW-5", allDocuments(),
                boundaryPassingInspections()));
        reviewService.evaluate("B-FLOW-5");

        // 第 6 日不允许期满生效
        clock.setFixed(Instant.parse("2026-09-13T23:59:59Z"));
        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> reviewService.settlePublicity("B-FLOW-5"));
        assertEquals(409, ex.getHttpStatus());
        assertEquals(ReviewStatus.PUBLICIZING.name(), reviewService.getReview("B-FLOW-5").status());

        // 恰第 7 日生效后重复结算幂等成功
        clock.setFixed(Instant.parse("2026-09-14T00:00:00Z"));
        ServiceResult first = reviewService.settlePublicity("B-FLOW-5");
        ServiceResult replay = reviewService.settlePublicity("B-FLOW-5");
        assertEquals(200, first.httpStatus());
        assertEquals(200, replay.httpStatus());
        assertEquals("EFFECTIVE", asView(replay).publicity().status());
    }

    @Test
    void rejectedBatchIsTerminalAndLateDocumentsOnlyRecordedAfterArchive() {
        clock.setFixed(Instant.parse("2026-09-07T02:00:00Z"));
        // 无菌不合格（关键项）
        reviewService.declare(declareRequest("req-d-6", "B-FLOW-6", allDocuments(),
                inspections("4.0", "有菌", "0.10")));
        ServiceResult evaluated = reviewService.evaluate("B-FLOW-6");
        assertEquals(200, evaluated.httpStatus());
        assertEquals(ReviewStatus.REJECTED.name(), asView(evaluated).status());
        assertFalse(asView(evaluated).inspections().stream()
                .filter(i -> i.itemCode().equals("STERILITY")).findFirst().orElseThrow().qualified());

        // 非归档终态登记迟到资料应被拒绝
        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> reviewService.reportLateDocument("B-FLOW-6",
                        new com.example.starter.dto.LateDocumentRequest(List.of("BATCH_RECORD"))));
        assertEquals(409, ex.getHttpStatus());
    }
}
