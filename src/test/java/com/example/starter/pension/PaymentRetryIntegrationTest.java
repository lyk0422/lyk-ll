package com.example.starter.pension;

import com.example.starter.pension.api.PaymentInstructionRequest;
import com.example.starter.pension.api.PaymentResultResponse;
import com.example.starter.pension.api.PayoutRequest;
import com.example.starter.pension.api.PlanResponse;
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
 * 出款失败可重试、失败指令幂等重放、每期最多出款一次的 H2 事务测试。
 */
class PaymentRetryIntegrationTest extends PensionIntegrationTestBase {

    @Test
    @DisplayName("失败不出款不结清，换新指令重试成功，每期最多出款一次")
    void failThenRetrySucceedsAndOnlyOnePayout() {
        member("M2", "李四", LocalDate.of(2018, 9, 25), MemberStatus.RETIRED);
        // 满 8 年 -> 企业 100% 归属，未归属为 0
        contribution("M2", ContributionType.PERSONAL, 100L, LocalDate.of(2020, 1, 1), "2020-01");
        contribution("M2", ContributionType.ENTERPRISE, 200L, LocalDate.of(2020, 1, 1), "2020-01");

        PlanResponse plan = pensionService.applyPayout(new PayoutRequest(
                "REQ-2", "M2", PayoutReason.RETIRE, InstallmentType.LUMP_SUM, TODAY));
        assertThat(plan.vestedRatioPercent()).isEqualTo("100.00");
        assertThat(plan.vestedTotalCents()).isEqualTo(300L);
        assertThat(plan.unvestedReturnCents()).isZero();
        assertThat(plan.installments()).hasSize(1);
        assertThat(plan.installments().get(0).amountCents()).isEqualTo(300L);

        // 首次指令模拟失败：不出款、期次 FAILED、计划不结清
        PaymentResultResponse failed = pensionService.payInstallment(new PaymentInstructionRequest(
                "PAY-FAIL", plan.planNo(), 1, true));
        assertThat(failed.success()).isFalse();
        assertThat(failed.replayed()).isFalse();
        assertThat(failed.paidAt()).isNull();
        assertThat(countLedger("MEMBER_PAYOUT")).isZero();
        assertThat(pensionService.getPlan(plan.planNo()).status()).isEqualTo(PlanStatus.ACCEPTED);

        // 失败指令同键重放返回首次失败结果，不再尝试出款
        PaymentResultResponse failedReplay = pensionService.payInstallment(new PaymentInstructionRequest(
                "PAY-FAIL", plan.planNo(), 1, false));
        assertThat(failedReplay.success()).isFalse();
        assertThat(failedReplay.replayed()).isTrue();
        assertThat(countLedger("MEMBER_PAYOUT")).isZero();

        // 换新 requestId 重试成功
        PaymentResultResponse success = pensionService.payInstallment(new PaymentInstructionRequest(
                "PAY-OK", plan.planNo(), 1, false));
        assertThat(success.success()).isTrue();
        assertThat(success.replayed()).isFalse();

        PlanResponse settled = pensionService.getPlan(plan.planNo());
        assertThat(settled.status()).isEqualTo(PlanStatus.SETTLED);
        assertThat(settled.installments().get(0).attempts()).isEqualTo(2);
        // 满 8 年无未归属金额，不产生企业划回流水
        assertThat(countLedger("ENTERPRISE_RETURN")).isZero();
        // 每期最多出款一次：只有一条成员出款流水，金额 300
        assertThat(countLedger("MEMBER_PAYOUT")).isEqualTo(1L);
        assertThat(sumLedger("MEMBER_PAYOUT")).isEqualTo(300L);
        assertThat(settled.enterpriseReturned()).isFalse();

        // 成功指令同键重放
        PaymentResultResponse successReplay = pensionService.payInstallment(new PaymentInstructionRequest(
                "PAY-OK", plan.planNo(), 1, true));
        assertThat(successReplay.success()).isTrue();
        assertThat(successReplay.replayed()).isTrue();
        assertThat(successReplay.paidAt()).isEqualTo(success.paidAt());
        assertThat(countLedger("MEMBER_PAYOUT")).isEqualTo(1L);

        // 同一指令 requestId 改期次 -> 409，且不产生新指令、不新增出款
        assertThatThrownBy(() -> pensionService.payInstallment(new PaymentInstructionRequest(
                "PAY-OK", plan.planNo(), 2, false)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((ApiException) ex).getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                });
        Long instructions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payout_instruction", Long.class);
        assertThat(instructions).isEqualTo(2L); // 仅 PAY-FAIL 与 PAY-OK 两条
        assertThat(countLedger("MEMBER_PAYOUT")).isEqualTo(1L);
    }

    private long countLedger(String movementType) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_ledger WHERE movement_type = ?",
                Long.class, movementType);
        return value == null ? 0L : value;
    }

    private long sumLedger(String movementType) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_cents),0) FROM account_ledger WHERE movement_type = ?",
                Long.class, movementType);
        return value == null ? 0L : value;
    }
}
