package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.api.dto.DeclarePlanRequest;
import com.example.starter.api.dto.PlanResponse;
import com.example.starter.api.dto.ReviewDecision;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.core.ApiException;
import com.example.starter.domain.PlanStatus;
import com.example.starter.support.TestFixtures;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 申报→审核硬性规则：40% 恰达合法、氯硫加权边界、许可过期/吊销/不存在整单拒绝。
 */
class PlanReviewIntegrationTest extends AbstractIntegrationTest {

    private void seedValidLicense() {
        licenseService.upsert(TestFixtures.license(TestFixtures.LICENSE,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
    }

    private PlanResponse declareAndReview(String requestId, String heatRatio) {
        return declareAndReview(TestFixtures.declareRequest(requestId, heatRatio));
    }

    private PlanResponse declareAndReview(DeclarePlanRequest request) {
        PlanResponse declared = planService.declare(request);
        return planService.review(declared.id(), new ReviewRequest(ReviewDecision.APPROVE, null));
    }

    @Test
    void heatRatioExactly40PercentIsApproved() {
        seedValidLicense();
        PlanResponse plan = declareAndReview("req-40-exact", "0.4000");
        assertThat(plan.status()).isEqualTo(PlanStatus.APPROVED.name());
    }

    @Test
    void heatRatioOver40PercentRejectsWholeOrder() {
        seedValidLicense();
        PlanResponse declared = planService.declare(TestFixtures.declareRequest("req-41", "0.4001"));
        PlanResponse reviewed = planService.review(declared.id(),
                new ReviewRequest(ReviewDecision.APPROVE, null));
        assertThat(reviewed.status()).isEqualTo(PlanStatus.REJECTED.name());
        assertThat(reviewed.rejectReason()).contains("替代燃料热量替代率");
    }

    @Test
    void chlorineWeightedExactlyAtLimitIsApproved() {
        seedValidLicense();
        // 两种燃料各 1000 kg/h、氯 0.03%：Σ 1000×0.03/100 ×2 = 0.6 kg/h，恰等于限值。
        DeclarePlanRequest request = new DeclarePlanRequest("req-cl-eq", TestFixtures.KILN,
                TestFixtures.LICENSE, new java.math.BigDecimal("0.4000"), new java.math.BigDecimal("0.1000"),
                List.of(TestFixtures.fuel("煤粉", false, "1000", "5500", "0.03", "0.30"),
                        TestFixtures.fuel("RDF", true, "1000", "3200", "0.03", "0.30")));
        PlanResponse plan = declareAndReview(request);
        assertThat(plan.status()).isEqualTo(PlanStatus.APPROVED.name());
        assertThat(plan.chlorineKgH()).isEqualByComparingTo("0.6000");
    }

    @Test
    void chlorineWeightedOverLimitRejectsWholeOrder() {
        seedValidLicense();
        // Σ 1000×0.04/100 ×2 = 0.8 kg/h，超过 0.6 限值。
        DeclarePlanRequest request = new DeclarePlanRequest("req-cl-over", TestFixtures.KILN,
                TestFixtures.LICENSE, new java.math.BigDecimal("0.4000"), new java.math.BigDecimal("0.1000"),
                List.of(TestFixtures.fuel("煤粉", false, "1000", "5500", "0.04", "0.30"),
                        TestFixtures.fuel("RDF", true, "1000", "3200", "0.04", "0.30")));
        PlanResponse declared = planService.declare(request);
        PlanResponse reviewed = planService.review(declared.id(),
                new ReviewRequest(ReviewDecision.APPROVE, null));
        assertThat(reviewed.status()).isEqualTo(PlanStatus.REJECTED.name());
        assertThat(reviewed.rejectReason()).contains("氯");
    }

    @Test
    void sulfurWeightedExactlyAtLimitIsApproved() {
        seedValidLicense();
        // 硫：Σ 1000×0.60/100 ×2 = 12 kg/h，恰等于限值。
        DeclarePlanRequest request = new DeclarePlanRequest("req-s-eq", TestFixtures.KILN,
                TestFixtures.LICENSE, new java.math.BigDecimal("0.4000"), new java.math.BigDecimal("0.1000"),
                List.of(TestFixtures.fuel("煤粉", false, "1000", "5500", "0.01", "0.60"),
                        TestFixtures.fuel("RDF", true, "1000", "3200", "0.01", "0.60")));
        PlanResponse plan = declareAndReview(request);
        assertThat(plan.status()).isEqualTo(PlanStatus.APPROVED.name());
        assertThat(plan.sulfurKgH()).isEqualByComparingTo("12.0000");
    }

    @Test
    void sulfurWeightedOverLimitRejectsWholeOrder() {
        seedValidLicense();
        DeclarePlanRequest request = new DeclarePlanRequest("req-s-over", TestFixtures.KILN,
                TestFixtures.LICENSE, new java.math.BigDecimal("0.4000"), new java.math.BigDecimal("0.1000"),
                List.of(TestFixtures.fuel("煤粉", false, "1000", "5500", "0.01", "0.61"),
                        TestFixtures.fuel("RDF", true, "1000", "3200", "0.01", "0.61")));
        PlanResponse declared = planService.declare(request);
        PlanResponse reviewed = planService.review(declared.id(),
                new ReviewRequest(ReviewDecision.APPROVE, null));
        assertThat(reviewed.status()).isEqualTo(PlanStatus.REJECTED.name());
        assertThat(reviewed.rejectReason()).contains("硫");
    }

    @Test
    void expiredLicenseRejectsWholeOrder() {
        // 审核时刻 2026-09-25，许可前一日到期：过期直接拒绝。
        licenseService.upsert(TestFixtures.license(TestFixtures.LICENSE,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 24)));
        PlanResponse declared = planService.declare(TestFixtures.declareRequest("req-lic-exp", "0.4000"));
        PlanResponse reviewed = planService.review(declared.id(),
                new ReviewRequest(ReviewDecision.APPROVE, null));
        assertThat(reviewed.status()).isEqualTo(PlanStatus.REJECTED.name());
        assertThat(reviewed.rejectReason()).contains("过期");
    }

    @Test
    void revokedLicenseRejectsWholeOrder() {
        licenseService.upsert(new com.example.starter.api.dto.LicenseUpsertRequest(
                TestFixtures.LICENSE, "合成持证单位",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), true));
        PlanResponse declared = planService.declare(TestFixtures.declareRequest("req-lic-rev", "0.4000"));
        PlanResponse reviewed = planService.review(declared.id(),
                new ReviewRequest(ReviewDecision.APPROVE, null));
        assertThat(reviewed.status()).isEqualTo(PlanStatus.REJECTED.name());
        assertThat(reviewed.rejectReason()).contains("吊销");
    }

    @Test
    void missingLicenseRejectsWholeOrder() {
        PlanResponse declared = planService.declare(TestFixtures.declareRequest("req-lic-none", "0.4000"));
        PlanResponse reviewed = planService.review(declared.id(),
                new ReviewRequest(ReviewDecision.APPROVE, null));
        assertThat(reviewed.status()).isEqualTo(PlanStatus.REJECTED.name());
        assertThat(reviewed.rejectReason()).contains("许可不存在");
    }

    @Test
    void rejectTerminalStateCannotBeReviewedAgain() {
        seedValidLicense();
        PlanResponse declared = planService.declare(TestFixtures.declareRequest("req-twice", "0.4000"));
        planService.review(declared.id(), new ReviewRequest(ReviewDecision.REJECT, "人工拒绝"));
        assertThatThrownBy(() -> planService.review(declared.id(),
                new ReviewRequest(ReviewDecision.APPROVE, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("DECLARED");
    }
}
