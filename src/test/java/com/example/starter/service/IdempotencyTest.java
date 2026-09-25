package com.example.starter.service;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.domain.ReviewStatus;
import com.example.starter.dto.CorrectRequest;
import com.example.starter.dto.DeclareRequest;
import com.example.starter.dto.IssueRequest;
import com.example.starter.dto.ReviewView;
import com.example.starter.dto.ServiceResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * requestId 幂等测试：同键同参重放返回首次结果，同键异参 409 且不改变审查状态。
 */
class IdempotencyTest extends AbstractIntegrationTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void sameRequestIdSameParametersReplaysFirstResult() {
        clock.setFixed(Instant.parse("2026-09-07T02:00:00Z"));
        DeclareRequest request = declareRequest("idem-1", "B-IDEM-1",
                allDocuments(), boundaryPassingInspections());

        ServiceResult first = reviewService.declare(request);
        ServiceResult replay = reviewService.declare(request);
        assertEquals(200, first.httpStatus());
        assertEquals(200, replay.httpStatus());
        assertEquals(asView(first).reviewId(), asView(replay).reviewId(),
                "同键同参必须返回首次受理的同一流程");
        assertEquals(1, countReviews("B-IDEM-1"), "重放不得产生第二个审查流程");
    }

    @Test
    void sameRequestIdDifferentParametersConflictsWithoutStateChange() {
        clock.setFixed(Instant.parse("2026-09-07T02:00:00Z"));
        DeclareRequest first = declareRequest("idem-2", "B-IDEM-2",
                allDocuments(), boundaryPassingInspections());
        reviewService.declare(first);

        // 同 requestId，变更效力实测值
        DeclareRequest tampered = new DeclareRequest("idem-2", "合成生物制品厂", "重组冠状病毒疫苗",
                "B-IDEM-2", allDocuments(), inspections("4.5", "无菌", "0.10"));
        ServiceResult conflict = reviewService.declare(tampered);
        assertEquals(409, conflict.httpStatus());

        ReviewView unchanged = reviewService.getReview("B-IDEM-2");
        assertEquals("4.0", unchanged.inspections().stream()
                .filter(i -> i.itemCode().equals("POTENCY")).findFirst().orElseThrow().measuredValue(),
                "同键异参不得改变首次审查状态与数据");
        assertEquals(1, countReviews("B-IDEM-2"));
    }

    @Test
    void correctionAndIssueReplayAndConflict() {
        clock.setFixed(LocalDate.of(2026, 9, 7).atTime(10, 0).atZone(SHANGHAI).toInstant());
        List<String> docs = new java.util.ArrayList<>(allDocuments());
        docs.remove(com.example.starter.catalog.DocumentCatalog.LABEL_INSERT);
        reviewService.declare(declareRequest("idem-3-d", "B-IDEM-3", docs,
                boundaryPassingInspections()));

        clock.setFixed(LocalDate.of(2026, 9, 14).atTime(10, 0).atZone(SHANGHAI).toInstant());
        CorrectRequest correction = new CorrectRequest("idem-3-c",
                List.of(com.example.starter.catalog.DocumentCatalog.LABEL_INSERT));
        ServiceResult c1 = reviewService.correct("B-IDEM-3", correction);
        ServiceResult c2 = reviewService.correct("B-IDEM-3", correction);
        assertEquals(200, c1.httpStatus());
        assertEquals(200, c2.httpStatus());

        // 同键异参：补不同资料
        ServiceResult conflict = reviewService.correct("B-IDEM-3",
                new CorrectRequest("idem-3-c", List.of("BATCH_RECORD")));
        assertEquals(409, conflict.httpStatus());
        assertEquals(ReviewStatus.INSPECTION_CHECKED.name(), reviewService.getReview("B-IDEM-3").status());

        reviewService.evaluate("B-IDEM-3");
        // 评定时刻为 Shanghai 2026-09-14 10:00（UTC 2026-09-14 02:00），公示期满日为 UTC 2026-09-21
        clock.setFixed(Instant.parse("2026-09-21T00:00:00Z"));
        reviewService.settlePublicity("B-IDEM-3");

        // 签发：首次签发 + 同键重放幂等成功
        IssueRequest issue = new IssueRequest("idem-3-i");
        ServiceResult i1 = reviewService.issue("B-IDEM-3", issue);
        ServiceResult i2 = reviewService.issue("B-IDEM-3", issue);
        assertEquals(200, i1.httpStatus());
        assertEquals(200, i2.httpStatus());
        assertEquals(ReviewStatus.ISSUED.name(), reviewService.getReview("B-IDEM-3").status());
        assertNotNull(reviewService.getReview("B-IDEM-3").issuedAt());
    }

    private int countReviews(String batchNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_review WHERE batch_no = ?", Integer.class, batchNo);
        return count == null ? 0 : count;
    }
}
