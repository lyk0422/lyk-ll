package com.example.starter.pension;

import com.example.starter.pension.api.PaymentInstructionRequest;
import com.example.starter.pension.api.PayoutRequest;
import com.example.starter.pension.api.PlanResponse;
import com.example.starter.pension.domain.ContributionType;
import com.example.starter.pension.domain.InstallmentStatus;
import com.example.starter.pension.domain.InstallmentType;
import com.example.starter.pension.domain.MemberStatus;
import com.example.starter.pension.domain.PlanStatus;
import com.example.starter.pension.domain.PayoutReason;
import com.example.starter.pension.repo.LedgerRepository;
import com.example.starter.pension.repo.jdbc.JdbcLedgerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 末期“成员出款 + 未归属企业缴费划回”同事务原子性测试：
 * 通过仅作用于本类的故障包装令企业划回失败，验证成员出款、期次状态、结清全部回滚。
 */
class EnterpriseReturnAtomicityIntegrationTest extends PensionIntegrationTestBase {

    /** 故障开关，默认关闭。 */
    private static final class FaultGate {
        static volatile boolean failEnterpriseReturn;
    }

    /** 仅在本测试类生效的故障注入：企业划回时抛出运行时异常。 */
    @TestConfiguration
    static class FaultInjectionConfig {

        @Bean
        @Primary
        LedgerRepository faultyLedger(JdbcLedgerRepository delegate) {
            return new LedgerRepository() {
                @Override
                public int insertMemberPayout(long planId, long installmentId, long amountCents) {
                    return delegate.insertMemberPayout(planId, installmentId, amountCents);
                }

                @Override
                public int insertEnterpriseReturn(long planId, long amountCents) {
                    if (FaultGate.failEnterpriseReturn) {
                        throw new IllegalStateException("模拟企业账户划回通道故障");
                    }
                    return delegate.insertEnterpriseReturn(planId, amountCents);
                }

                @Override
                public long sumMemberPayouts(long planId) {
                    return delegate.sumMemberPayouts(planId);
                }

                @Override
                public long sumEnterpriseReturns(long planId) {
                    return delegate.sumEnterpriseReturns(planId);
                }
            };
        }
    }

    @Test
    @DisplayName("企业划回失败时同事务回滚成员出款与结清，故障解除后同键支付成功")
    void enterpriseReturnFailureRollsBackMemberPayout() {
        member("X1", "原子性", LocalDate.of(2023, 9, 25), MemberStatus.RESIGNED);
        contribution("X1", ContributionType.PERSONAL, 50L, LocalDate.of(2024, 1, 1), "2024-01");
        contribution("X1", ContributionType.ENTERPRISE, 100L, LocalDate.of(2024, 1, 1), "2024-01");
        PlanResponse plan = pensionService.applyPayout(new PayoutRequest(
                "X1-REQ", "X1", PayoutReason.RESIGN, InstallmentType.LUMP_SUM, TODAY));
        // 50% 归属：个人 50 + 企业 50 = 100 支付，未归属企业 50 划回
        assertThat(plan.vestedTotalCents()).isEqualTo(100L);
        assertThat(plan.unvestedReturnCents()).isEqualTo(50L);

        // 开启企业划回故障：末期支付整体失败并回滚
        FaultGate.failEnterpriseReturn = true;
        try {
            assertThatThrownBy(() -> pensionService.payInstallment(new PaymentInstructionRequest(
                    "X1-PAY", plan.planNo(), 1, false)))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("企业账户划回通道故障");
        } finally {
            FaultGate.failEnterpriseReturn = false;
        }

        // 同事务回滚：成员未出款、企业未划回、期次仍待付、计划未结清、指令未残留
        assertThat(countLedger("MEMBER_PAYOUT")).isZero();
        assertThat(countLedger("ENTERPRISE_RETURN")).isZero();
        PlanResponse afterFailure = pensionService.getPlan(plan.planNo());
        assertThat(afterFailure.status()).isEqualTo(PlanStatus.ACCEPTED);
        assertThat(afterFailure.enterpriseReturned()).isFalse();
        assertThat(afterFailure.installments().get(0).status())
                .isEqualTo(InstallmentStatus.PENDING.name());
        assertThat(afterFailure.installments().get(0).attempts()).isZero();
        Long instructions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payout_instruction WHERE request_id = ?",
                Long.class, "X1-PAY");
        assertThat(instructions).isZero();

        // 故障解除后，同一指令 requestId 可重新提交并成功（回滚未留幂等残留）
        var success = pensionService.payInstallment(new PaymentInstructionRequest(
                "X1-PAY", plan.planNo(), 1, false));
        assertThat(success.success()).isTrue();
        assertThat(success.replayed()).isFalse();

        PlanResponse settled = pensionService.getPlan(plan.planNo());
        assertThat(settled.status()).isEqualTo(PlanStatus.SETTLED);
        assertThat(settled.enterpriseReturned()).isTrue();
        assertThat(countLedger("MEMBER_PAYOUT")).isEqualTo(1L);
        assertThat(sumLedger("MEMBER_PAYOUT")).isEqualTo(100L);
        assertThat(countLedger("ENTERPRISE_RETURN")).isEqualTo(1L);
        assertThat(sumLedger("ENTERPRISE_RETURN")).isEqualTo(50L);
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
