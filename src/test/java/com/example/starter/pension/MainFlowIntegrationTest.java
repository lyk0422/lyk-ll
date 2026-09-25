package com.example.starter.pension;

import com.example.starter.pension.api.PaymentInstructionRequest;
import com.example.starter.pension.api.PaymentResultResponse;
import com.example.starter.pension.api.PayoutRequest;
import com.example.starter.pension.api.PlanResponse;
import com.example.starter.pension.api.SettleResponse;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 主流程：司龄恰满档位的归属快照、分期尾差、逐期支付、末期划回同事务与自动结清。
 */
class MainFlowIntegrationTest extends PensionIntegrationTestBase {

    @Test
    @DisplayName("恰满3年50%归属：100分分12期尾差归末期，末期支付同事务划回并自动结清")
    void vestingSnapshotInstallmentsPaymentAndSettle() {
        // 恰满 3 年（2023-09-25 入职，2026-09-25 申请）
        member("M1", "张三", LocalDate.of(2023, 9, 25), MemberStatus.RESIGNED);
        contribution("M1", ContributionType.PERSONAL, 50L, LocalDate.of(2024, 1, 31), "2024-01");
        // 企业 101 分的 50% = 50.5 -> 50（不足 1 分舍去），未归属 51 分
        contribution("M1", ContributionType.ENTERPRISE, 101L, LocalDate.of(2024, 1, 31), "2024-01");

        PlanResponse plan = pensionService.applyPayout(new PayoutRequest(
                "REQ-1", "M1", PayoutReason.RESIGN, InstallmentType.MONTHLY_12, TODAY));

        // 归属快照
        assertThat(plan.planNo()).startsWith("PL-");
        assertThat(plan.hireYears()).isEqualTo(3);
        assertThat(plan.vestedRatioPercent()).isEqualTo("50.00");
        assertThat(plan.personalAmountCents()).isEqualTo(50L);
        assertThat(plan.enterpriseAmountCents()).isEqualTo(101L);
        assertThat(plan.vestedEnterpriseCents()).isEqualTo(50L);
        assertThat(plan.vestedTotalCents()).isEqualTo(100L);
        assertThat(plan.unvestedReturnCents()).isEqualTo(51L);
        assertThat(plan.status()).isEqualTo(PlanStatus.ACCEPTED);
        assertThat(plan.enterpriseReturned()).isFalse();
        assertThat(plan.supplementary()).isFalse();
        assertThat(plan.originalPlanNo()).isNull();

        // 分期：100/12 = 8，前 11 期 8 分，末期 12 分（尾差）
        List<PlanResponse.InstallmentView> installments = plan.installments();
        assertThat(installments).hasSize(12);
        for (int i = 1; i <= 11; i++) {
            PlanResponse.InstallmentView v = installments.get(i - 1);
            assertThat(v.seqNo()).isEqualTo(i);
            assertThat(v.amountCents()).isEqualTo(8L);
            assertThat(v.dueDate()).isEqualTo(LocalDate.of(2026, 9, 1).plusMonths(i - 1L));
            assertThat(v.status()).isEqualTo("PENDING");
        }
        assertThat(installments.get(11).amountCents()).isEqualTo(12L);
        assertThat(installments.get(11).dueDate()).isEqualTo(LocalDate.of(2027, 8, 1));
        long sum = installments.stream().mapToLong(PlanResponse.InstallmentView::amountCents).sum();
        assertThat(sum).isEqualTo(plan.vestedTotalCents());

        // 前 11 期支付
        for (int seq = 1; seq <= 11; seq++) {
            PaymentResultResponse result = pensionService.payInstallment(new PaymentInstructionRequest(
                    "PAY-" + seq, plan.planNo(), seq, false));
            assertThat(result.success()).isTrue();
            assertThat(result.replayed()).isFalse();
            assertThat(result.amountCents()).isEqualTo(8L);
        }
        PlanResponse mid = pensionService.getPlan(plan.planNo());
        assertThat(mid.status()).isEqualTo(PlanStatus.IN_PROGRESS);
        assertThat(mid.enterpriseReturned()).isFalse();
        // 末期之前不得划回企业
        assertThat(countLedger("ENTERPRISE_RETURN")).isZero();
        assertThat(sumLedger("MEMBER_PAYOUT")).isEqualTo(88L);

        // 末期支付：成员出款与企业划回同事务，并自动结清
        PaymentResultResponse last = pensionService.payInstallment(new PaymentInstructionRequest(
                "PAY-12", plan.planNo(), 12, false));
        assertThat(last.success()).isTrue();
        assertThat(last.amountCents()).isEqualTo(12L);
        assertThat(last.paidAt()).isNotNull();

        PlanResponse settled = pensionService.getPlan(plan.planNo());
        assertThat(settled.status()).isEqualTo(PlanStatus.SETTLED);
        assertThat(settled.enterpriseReturned()).isTrue();
        assertThat(settled.settledAt()).isNotNull();
        assertThat(sumLedger("MEMBER_PAYOUT")).isEqualTo(100L);
        assertThat(sumLedger("ENTERPRISE_RETURN")).isEqualTo(51L);
        assertThat(countLedger("ENTERPRISE_RETURN")).isEqualTo(1L);
        assertThat(countLedger("MEMBER_PAYOUT")).isEqualTo(12L);

        // 重复结清幂等成功
        SettleResponse settleAgain = pensionService.settle(plan.planNo());
        assertThat(settleAgain.status()).isEqualTo(PlanStatus.SETTLED);
        assertThat(settleAgain.replayed()).isTrue();
        assertThat(settleAgain.paidTotalCents()).isEqualTo(100L);
        assertThat(settleAgain.returnedTotalCents()).isEqualTo(51L);

        // 已结清计划末期再支付不允许（每期最多出款一次）
        assertThatThrownBy(() -> pensionService.payInstallment(new PaymentInstructionRequest(
                "PAY-12-NEW", plan.planNo(), 12, false)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        // 同键重放返回首次结果且不产生新指令
        PaymentResultResponse replay = pensionService.payInstallment(new PaymentInstructionRequest(
                "PAY-1", plan.planNo(), 1, false));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.success()).isTrue();
        assertThat(countLedger("MEMBER_PAYOUT")).isEqualTo(12L);
    }

    private long sumLedger(String movementType) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_cents),0) FROM account_ledger WHERE movement_type = ?",
                Long.class, movementType);
        return value == null ? 0L : value;
    }

    private long countLedger(String movementType) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_ledger WHERE movement_type = ?",
                Long.class, movementType);
        return value == null ? 0L : value;
    }
}
