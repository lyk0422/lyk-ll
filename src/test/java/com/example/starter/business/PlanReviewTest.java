package com.example.starter.business;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.api.dto.PlanResultResponse;
import com.example.starter.api.dto.PlanSubmitRequest;
import com.example.starter.domain.BusinessException;
import com.example.starter.domain.RejectCategory;
import com.example.starter.service.CementService;
import com.example.starter.support.TestFixtures;

/**
 * 申报与合成审核：替代率恰40%合法、氯硫质量加权、许可过期整单拒绝。
 */
class PlanReviewTest extends AbstractIntegrationTest {

    @Autowired
    private CementService service;

    private PlanSubmitRequest request(String requestId, String rate, String license,
                                      BigDecimal clMass, String clPct, String sPct) {
        LocalDateTime start = LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0);
        var fuel = TestFixtures.highClSulfurFuel("HW-FUEL", clMass, new BigDecimal(clPct),
                new BigDecimal(sPct));
        return TestFixtures.submit(requestId, KILN, license, rate, start, start.plusHours(8),
                List.of(fuel));
    }

    @Test
    void exactly_40_percent_substitution_is_accepted() {
        PlanResultResponse resp = service.submit(
                request("REQ-RATE-40", "0.4000", VALID_LICENSE,
                        new BigDecimal("1000"), "1.00", "1.00"));
        assertThat(resp.accepted()).isTrue();
        assertThat(resp.status()).isEqualTo("APPROVED");
        assertThat(resp.rejectCategory()).isNull();
    }

    @Test
    void above_40_percent_substitution_is_rejected() {
        PlanResultResponse resp = service.submit(
                request("REQ-RATE-41", "0.4001", VALID_LICENSE,
                        new BigDecimal("1000"), "1.00", "1.00"));
        assertThat(resp.accepted()).isFalse();
        assertThat(resp.rejectCategory()).isEqualTo("RATE_EXCEEDED");
    }

    @Test
    void weighted_chlorine_exactly_at_limit_is_accepted() {
        // 16000kg × 5% = 800kg 恰等于氯限值；硫 16000×5%=800kg < 15000kg
        PlanResultResponse resp = service.submit(
                request("REQ-CL-EQ", "0.3000", VALID_LICENSE,
                        new BigDecimal("16000"), "5.00", "5.00"));
        assertThat(resp.accepted()).isTrue();
        assertThat(resp.chlorineTotalKg()).isEqualByComparingTo("800.000");
        assertThat(resp.sulfurTotalKg()).isEqualByComparingTo("800.000");
    }

    @Test
    void weighted_chlorine_above_limit_returns_rejected_result() {
        PlanResultResponse resp = service.submit(
                request("REQ-CL-OVER2", "0.3000", VALID_LICENSE,
                        new BigDecimal("20000"), "5.00", "5.00"));
        assertThat(resp.accepted()).isFalse();
        assertThat(resp.status()).isEqualTo("REJECTED");
        assertThat(resp.rejectCategory()).isEqualTo("CHLORINE_EXCEEDED");
        assertThat(resp.chlorineTotalKg()).isEqualByComparingTo("1000.000");
    }

    @Test
    void weighted_sulfur_above_limit_rejects_whole_order() {
        // 200000kg × 10% = 20000kg > 15000kg；氯 200000×0.1%=200kg < 800kg
        PlanResultResponse resp = service.submit(
                request("REQ-S-OVER", "0.3000", VALID_LICENSE,
                        new BigDecimal("200000"), "0.10", "10.00"));
        assertThat(resp.accepted()).isFalse();
        assertThat(resp.rejectCategory()).isEqualTo("SULFUR_EXCEEDED");
        assertThat(resp.sulfurTotalKg()).isEqualByComparingTo("20000.000");
    }

    @Test
    void expired_license_is_rejected() {
        PlanResultResponse resp = service.submit(
                request("REQ-LIC-EXP", "0.3000", EXPIRED_LICENSE,
                        new BigDecimal("1000"), "1.00", "1.00"));
        assertThat(resp.accepted()).isFalse();
        assertThat(resp.rejectCategory()).isEqualTo("LICENSE_INVALID");
        assertThat(resp.rejectReason()).contains("过期");
    }

    @Test
    void unknown_license_is_rejected() {
        PlanResultResponse resp = service.submit(
                request("REQ-LIC-NO", "0.3000", "HW-NOT-EXIST",
                        new BigDecimal("1000"), "1.00", "1.00"));
        assertThat(resp.accepted()).isFalse();
        assertThat(resp.rejectCategory()).isEqualTo("LICENSE_INVALID");
    }

    @Test
    void non_high_cl_sulfur_fuel_is_excluded_from_weighted_totals() {
        LocalDateTime start = LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0);
        var req = TestFixtures.submit("REQ-NORMAL", KILN, VALID_LICENSE, "0.4000",
                start, start.plusHours(4), List.of(TestFixtures.normalFuel()));
        PlanResultResponse resp = service.submit(req);
        assertThat(resp.accepted()).isTrue();
        assertThat(resp.chlorineTotalKg()).isEqualByComparingTo("0.000");
        assertThat(resp.sulfurTotalKg()).isEqualByComparingTo("0.000");
    }
}
