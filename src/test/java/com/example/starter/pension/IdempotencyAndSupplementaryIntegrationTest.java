package com.example.starter.pension;

import com.example.starter.pension.api.PaymentInstructionRequest;
import com.example.starter.pension.api.PayoutRequest;
import com.example.starter.pension.api.PlanResponse;
import com.example.starter.pension.api.SupplementaryPayoutRequest;
import com.example.starter.pension.domain.ContributionType;
import com.example.starter.pension.domain.InstallmentType;
import com.example.starter.pension.domain.MemberStatus;
import com.example.starter.pension.domain.PlanStatus;
import com.example.starter.pension.domain.PayoutReason;
import com.example.starter.pension.web.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 支付申请幂等（同键同参/异参）、进行中唯一、事由校验及结清后补发引用测试。
 */
class IdempotencyAndSupplementaryIntegrationTest extends PensionIntegrationTestBase {

    @Test
    @DisplayName("申请同键同参返回首次结果，同键异参409且不产生新计划")
    void applyIdempotentSameKeyAndConflictOnDifferentParams() {
        member("M3", "王五", LocalDate.of(2021, 9, 25), MemberStatus.RESIGNED);
        contribution("M3", ContributionType.PERSONAL, 100L, LocalDate.of(2024, 1, 1), "2024-01");
        contribution("M3", ContributionType.ENTERPRISE, 100L, LocalDate.of(2024, 1, 1), "2024-01");

        PayoutRequest first = new PayoutRequest("REQ-3", "M3", PayoutReason.RESIGN,
                InstallmentType.MONTHLY_24, TODAY);
        PlanResponse plan1 = pensionService.applyPayout(first);

        // 同键同参：返回首次结果（同一计划号）
        PlanResponse plan2 = pensionService.applyPayout(new PayoutRequest("REQ-3", "M3",
                PayoutReason.RESIGN, InstallmentType.MONTHLY_24, TODAY));
        assertThat(plan2.planNo()).isEqualTo(plan1.planNo());
        assertThat(plan2.requestId()).isEqualTo("REQ-3");
        assertThat(countPlans()).isEqualTo(1L);

        // 同键异参（改分期方式）-> 409，且不产生新计划
        assertThatThrownBy(() -> pensionService.applyPayout(new PayoutRequest("REQ-3", "M3",
                PayoutReason.RESIGN, InstallmentType.LUMP_SUM, TODAY)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                });
        assertThat(countPlans()).isEqualTo(1L);

        // 同键异参（改申请日，改变司龄/归属）-> 409
        assertThatThrownBy(() -> pensionService.applyPayout(new PayoutRequest("REQ-3", "M3",
                PayoutReason.RESIGN, InstallmentType.MONTHLY_24, TODAY.plusDays(1))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("IDEMPOTENCY_CONFLICT"));
        assertThat(countPlans()).isEqualTo(1L);
    }

    @Test
    @DisplayName("进行中计划未结清时，新申请被拒绝；结清后补缴须补发引用原计划")
    void activePlanBlocksAndSupplementaryReferencesOriginal() {
        member("M4", "赵六", LocalDate.of(2018, 9, 25), MemberStatus.RETIRED);
        contribution("M4", ContributionType.PERSONAL, 100L, LocalDate.of(2024, 1, 1), "2024-01");
        contribution("M4", ContributionType.ENTERPRISE, 200L, LocalDate.of(2024, 1, 1), "2024-01");

        PlanResponse original = pensionService.applyPayout(new PayoutRequest(
                "REQ-4", "M4", PayoutReason.RETIRE, InstallmentType.LUMP_SUM, TODAY));
        assertThat(original.vestedTotalCents()).isEqualTo(300L);

        // 同一成员进行中只允许一个计划：新 requestId 也被拒绝
        assertThatThrownBy(() -> pensionService.applyPayout(new PayoutRequest(
                "REQ-4B", "M4", PayoutReason.RETIRE, InstallmentType.LUMP_SUM, TODAY)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("ACTIVE_PLAN_EXISTS"));

        // 结清前补发被拒（原计划非终态）
        assertThatThrownBy(() -> pensionService.applySupplementary(new SupplementaryPayoutRequest(
                "REQ-4S", "M4", original.planNo(), PayoutReason.RETIRE,
                InstallmentType.LUMP_SUM, TODAY)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("ORIGINAL_PLAN_NOT_SETTLED"));

        // 结清原计划
        pensionService.payInstallment(new PaymentInstructionRequest(
                "PAY-4", original.planNo(), 1, false));
        assertThat(pensionService.getPlan(original.planNo()).status())
                .isEqualTo(PlanStatus.SETTLED);

        // 结清后录入历史补缴缴费，再走普通申请仍被拒绝（必须补发）
        contribution("M4", ContributionType.PERSONAL, 70L, LocalDate.of(2026, 9, 1), "2026-09");
        contribution("M4", ContributionType.ENTERPRISE, 100L, LocalDate.of(2026, 9, 1), "2026-09");
        assertThatThrownBy(() -> pensionService.applyPayout(new PayoutRequest(
                "REQ-4C", "M4", PayoutReason.RETIRE, InstallmentType.LUMP_SUM, TODAY)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("MEMBER_ALREADY_HAS_PLAN"));

        // 补发：新计划号、引用原计划号、只含补缴快照（100% 归属 170 分），原计划不变
        PlanResponse supplementary = pensionService.applySupplementary(new SupplementaryPayoutRequest(
                "REQ-4S", "M4", original.planNo(), PayoutReason.RETIRE,
                InstallmentType.LUMP_SUM, TODAY));
        assertThat(supplementary.supplementary()).isTrue();
        assertThat(supplementary.originalPlanNo()).isEqualTo(original.planNo());
        assertThat(supplementary.planNo()).isNotEqualTo(original.planNo());
        assertThat(supplementary.personalAmountCents()).isEqualTo(70L);
        assertThat(supplementary.enterpriseAmountCents()).isEqualTo(100L);
        assertThat(supplementary.vestedEnterpriseCents()).isEqualTo(100L);
        assertThat(supplementary.vestedTotalCents()).isEqualTo(170L);
        assertThat(supplementary.unvestedReturnCents()).isZero();

        // 原计划快照不被改动
        PlanResponse originalAgain = pensionService.getPlan(original.planNo());
        assertThat(originalAgain.vestedTotalCents()).isEqualTo(300L);
        assertThat(originalAgain.status()).isEqualTo(PlanStatus.SETTLED);

        // 补发计划也可支付并独立结清
        pensionService.payInstallment(new PaymentInstructionRequest(
                "PAY-4S", supplementary.planNo(), 1, false));
        assertThat(pensionService.getPlan(supplementary.planNo()).status())
                .isEqualTo(PlanStatus.SETTLED);
    }

    @Test
    @DisplayName("支付事由与成员状态不一致时拒绝受理")
    void reasonMustMatchMemberStatus() {
        member("M5", "孙七", LocalDate.of(2023, 9, 25), MemberStatus.ACTIVE);
        contribution("M5", ContributionType.PERSONAL, 100L, LocalDate.of(2024, 1, 1), "2024-01");
        assertThatThrownBy(() -> pensionService.applyPayout(new PayoutRequest(
                "REQ-5", "M5", PayoutReason.RESIGN, InstallmentType.LUMP_SUM, TODAY)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    private long countPlans() {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payout_plan", Long.class);
        return value == null ? 0L : value;
    }
}
