package com.example.starter.service;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.dto.DeclareRequest;
import com.example.starter.dto.EnterpriseStatusView;
import com.example.starter.dto.InspectionInput;
import com.example.starter.dto.ServiceResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同企业同品种连续不合格测试：恰连续 3 批触发暂停受理并转核查；中间一批合格即清零。
 */
class SuspensionRuleTest extends AbstractIntegrationTest {

    private static final String ENTERPRISE = "连续不合格测试厂";
    private static final String PRODUCT = "暂停受理测试疫苗";

    private DeclareRequest failedBatchRequest(String requestId, String batchNo) {
        // 效力 3.0 低于 4.0 限值：关键项不合格
        List<InspectionInput> inputs = inspections("3.0", "无菌", "0.10");
        return new DeclareRequest(requestId, ENTERPRISE, PRODUCT, batchNo, allDocuments(), inputs);
    }

    private DeclareRequest passingBatchRequest(String requestId, String batchNo) {
        return new DeclareRequest(requestId, ENTERPRISE, PRODUCT, batchNo,
                allDocuments(), boundaryPassingInspections());
    }

    @Test
    void exactlyThreeConsecutiveFailedBatchesSuspendAcceptance() {
        clock.setFixed(Instant.parse("2026-09-01T00:00:00Z"));

        declareAndReject("s-1", "B-S-1");
        EnterpriseStatusView afterOne = reviewService.getEnterpriseStatus(ENTERPRISE, PRODUCT);
        assertEquals(1, afterOne.consecutiveFailed());
        assertFalse(afterOne.suspended(), "仅 1 批不合格不暂停");

        declareAndReject("s-2", "B-S-2");
        EnterpriseStatusView afterTwo = reviewService.getEnterpriseStatus(ENTERPRISE, PRODUCT);
        assertEquals(2, afterTwo.consecutiveFailed());
        assertFalse(afterTwo.suspended(), "连续 2 批不合格仍不暂停");

        declareAndReject("s-3", "B-S-3");
        EnterpriseStatusView afterThree = reviewService.getEnterpriseStatus(ENTERPRISE, PRODUCT);
        assertEquals(3, afterThree.consecutiveFailed());
        assertTrue(afterThree.suspended(), "恰连续 3 批不合格必须触发暂停受理");
        assertTrue(reviewService.getReview("B-S-3").status().equals("REJECTED"));

        // 暂停后新申报被拒绝（403），不产生审查流程
        ServiceResult blocked = reviewService.declare(failedBatchRequest("s-4", "B-S-4"));
        assertEquals(403, blocked.httpStatus());
        assertEquals(0, countReviews("B-S-4"));
    }

    @Test
    void passingBatchResetsConsecutiveFailureCount() {
        clock.setFixed(Instant.parse("2026-09-01T00:00:00Z"));
        declareAndReject("r-1", "B-R-1");
        declareAndReject("r-2", "B-R-2");

        // 第 3 批评定合格：计数清零，不暂停
        ServiceResult passed = reviewService.declare(passingBatchRequest("r-3", "B-R-3"));
        assertEquals(200, passed.httpStatus());
        reviewService.evaluate("B-R-3");
        EnterpriseStatusView status = reviewService.getEnterpriseStatus(ENTERPRISE, PRODUCT);
        assertEquals(0, status.consecutiveFailed());
        assertFalse(status.suspended());

        // 再连续两批不合格也不触发暂停（计数从 0 重新开始）
        declareAndReject("r-4", "B-R-4");
        declareAndReject("r-5", "B-R-5");
        assertFalse(reviewService.getEnterpriseStatus(ENTERPRISE, PRODUCT).suspended());
        assertEquals(2, reviewService.getEnterpriseStatus(ENTERPRISE, PRODUCT).consecutiveFailed());
    }

    private void declareAndReject(String requestId, String batchNo) {
        ServiceResult declared = reviewService.declare(failedBatchRequest(requestId, batchNo));
        assertEquals(200, declared.httpStatus());
        ServiceResult evaluated = reviewService.evaluate(batchNo);
        assertEquals(200, evaluated.httpStatus());
    }

    private int countReviews(String batchNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_review WHERE batch_no = ?", Integer.class, batchNo);
        return count == null ? 0 : count;
    }
}
