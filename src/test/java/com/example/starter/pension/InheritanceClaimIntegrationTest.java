package com.example.starter.pension;

import com.example.starter.pension.api.InheritanceClaimRequest;
import com.example.starter.pension.api.InheritanceClaimResponse;
import com.example.starter.pension.api.InheritanceCompleteResponse;
import com.example.starter.pension.api.LateContributionEventView;
import com.example.starter.pension.api.PaymentInstructionRequest;
import com.example.starter.pension.api.PayoutRequest;
import com.example.starter.pension.api.PlanResponse;
import com.example.starter.pension.domain.ClaimStatus;
import com.example.starter.pension.domain.ContributionType;
import com.example.starter.pension.domain.InstallmentStatus;
import com.example.starter.pension.domain.InstallmentType;
import com.example.starter.pension.domain.MemberStatus;
import com.example.starter.pension.domain.PlanStatus;
import com.example.starter.pension.domain.PayoutReason;
import com.example.starter.pension.web.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 继承申领 H2 集成测试：金额勾稽、180 日窗口、分期互斥同事务终止、
 * 并发唯一受理、requestId 幂等、完成终态与迟到缴费事件。
 */
class InheritanceClaimIntegrationTest extends PensionIntegrationTestBase {

    private static final int THREADS = 8;

    @Test
    @DisplayName("主流程：余额+未付分期本金勾稽受理，同事务终止剩余分期且已付不回溯，台账可复算")
    void claimTerminatesActivePlanAndReconciles() {
        member("I1", "继承甲", LocalDate.of(2020, 9, 25), MemberStatus.RETIRED);
        contribution("I1", ContributionType.PERSONAL, 1200L, LocalDate.of(2024, 1, 1), "2024-01");
        PlanResponse plan = pensionService.applyPayout(new PayoutRequest(
                "I1-REQ", "I1", PayoutReason.RETIRE, InstallmentType.MONTHLY_12, TODAY));
        assertThat(plan.vestedTotalCents()).isEqualTo(1200L);
        pensionService.payInstallment(new PaymentInstructionRequest("I1-PAY-1", plan.planNo(), 1, false));
        // 计划快照冻结后到账的缴费构成账户余额
        contribution("I1", ContributionType.PERSONAL, 500L, LocalDate.of(2026, 8, 1), "2026-08");

        InheritanceClaimResponse claim = pensionService.applyInheritanceClaim(new InheritanceClaimRequest(
                "I1-CLAIM", "I1", "继承人甲", TODAY.minusDays(100), 500L, 1100L, 1600L));

        assertThat(claim.status()).isEqualTo(ClaimStatus.ACCEPTED);
        assertThat(claim.replayed()).isFalse();
        assertThat(claim.accountBalanceCents()).isEqualTo(500L);
        assertThat(claim.unpaidInstallmentCents()).isEqualTo(1100L);
        assertThat(claim.claimAmountCents()).isEqualTo(1600L);
        assertThat(claim.terminatedPlanNo()).isEqualTo(plan.planNo());
        assertThat(claim.applyDate()).isEqualTo(TODAY);

        // 互斥：计划同事务终止，已支付期次不回溯，未付期次取消
        PlanResponse terminated = pensionService.getPlan(plan.planNo());
        assertThat(terminated.status()).isEqualTo(PlanStatus.TERMINATED);
        assertThat(terminated.installments().get(0).status()).isEqualTo(InstallmentStatus.PAID.name());
        for (int seq = 2; seq <= 12; seq++) {
            assertThat(terminated.installments().get(seq - 1).status())
                    .isEqualTo(InstallmentStatus.CANCELLED.name());
        }

        // 台账勾稽明细可复算：余额构成 + 11 笔未付期次本金 = 继承支付额
        assertThat(claim.ledger()).hasSize(12);
        long componentSum = claim.ledger().stream().mapToLong(InheritanceClaimResponse.LedgerEntry::amountCents).sum();
        assertThat(componentSum).isEqualTo(1600L);
        assertThat(claim.ledger().stream()
                .filter(e -> e.entryType().equals("UNPAID_INSTALLMENT")).count()).isEqualTo(11L);
        Long ledgerSum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_cents),0) FROM inheritance_ledger"
                        + " WHERE entry_type IN ('ACCOUNT_BALANCE','UNPAID_INSTALLMENT')",
                Long.class);
        assertThat(ledgerSum).isEqualTo(1600L);

        // 互斥后续：已终止计划不得再出款，成员不得再申请分期支付
        assertThatThrownBy(() -> pensionService.payInstallment(
                new PaymentInstructionRequest("I1-PAY-2", plan.planNo(), 2, false)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("PLAN_TERMINATED");
        assertThatThrownBy(() -> pensionService.applyPayout(new PayoutRequest(
                "I1-REQ-2", "I1", PayoutReason.RETIRE, InstallmentType.LUMP_SUM, TODAY)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("INHERITANCE_CLAIM_EXISTS");
    }

    @Test
    @DisplayName("申领窗口：恰第 180 日受理，第 181 日逾期拒绝，身故日晚于受理日拒绝")
    void claimWindowBoundary() {
        member("I2", "继承乙", LocalDate.of(2020, 1, 1), MemberStatus.RETIRED);
        InheritanceClaimResponse day180 = pensionService.applyInheritanceClaim(new InheritanceClaimRequest(
                "I2-CLAIM", "I2", "继承人乙", TODAY.minusDays(180), 0L, 0L, 0L));
        assertThat(day180.status()).isEqualTo(ClaimStatus.ACCEPTED);

        member("I3", "继承丙", LocalDate.of(2020, 1, 1), MemberStatus.RETIRED);
        assertThatThrownBy(() -> pensionService.applyInheritanceClaim(new InheritanceClaimRequest(
                "I3-CLAIM", "I3", "继承人丙", TODAY.minusDays(181), 0L, 0L, 0L)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("CLAIM_WINDOW_EXPIRED");

        member("I4", "继承丁", LocalDate.of(2020, 1, 1), MemberStatus.RETIRED);
        assertThatThrownBy(() -> pensionService.applyInheritanceClaim(new InheritanceClaimRequest(
                "I4-CLAIM", "I4", "继承人丁", TODAY.plusDays(1), 0L, 0L, 0L)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("DEATH_DATE_IN_FUTURE");
    }

    @Test
    @DisplayName("勾稽不一致拒绝且事务回滚：计划保持进行中、期次不变、无申领与台账落库")
    void reconcileMismatchRejectedAndRolledBack() {
        member("I5", "继承戊", LocalDate.of(2020, 9, 25), MemberStatus.RETIRED);
        contribution("I5", ContributionType.PERSONAL, 1200L, LocalDate.of(2024, 1, 1), "2024-01");
        PlanResponse plan = pensionService.applyPayout(new PayoutRequest(
                "I5-REQ", "I5", PayoutReason.RETIRE, InstallmentType.MONTHLY_12, TODAY));
        pensionService.payInstallment(new PaymentInstructionRequest("I5-PAY-1", plan.planNo(), 1, false));
        contribution("I5", ContributionType.PERSONAL, 500L, LocalDate.of(2026, 8, 1), "2026-08");

        // 两项合计与继承支付额不等
        assertThatThrownBy(() -> pensionService.applyInheritanceClaim(new InheritanceClaimRequest(
                "I5-CLAIM-1", "I5", "继承人戊", TODAY.minusDays(10), 500L, 1100L, 1601L)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("RECONCILE_MISMATCH");
        // 申报构成与系统核算不等
        assertThatThrownBy(() -> pensionService.applyInheritanceClaim(new InheritanceClaimRequest(
                "I5-CLAIM-2", "I5", "继承人戊", TODAY.minusDays(10), 499L, 1100L, 1599L)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("RECONCILE_MISMATCH");

        PlanResponse after = pensionService.getPlan(plan.planNo());
        assertThat(after.status()).isEqualTo(PlanStatus.IN_PROGRESS);
        assertThat(after.installments().get(1).status()).isEqualTo(InstallmentStatus.PENDING.name());
        assertThat(countWhere("SELECT COUNT(*) FROM inheritance_claim")).isZero();
        assertThat(countWhere("SELECT COUNT(*) FROM inheritance_ledger")).isZero();
    }

    @Test
    @DisplayName("幂等：同 requestId 同参返回首次申领，同键异参 409")
    void idempotentReplayAndConflict() {
        member("I6", "继承己", LocalDate.of(2020, 1, 1), MemberStatus.RETIRED);
        contribution("I6", ContributionType.PERSONAL, 300L, LocalDate.of(2024, 1, 1), "2024-01");

        InheritanceClaimResponse first = pensionService.applyInheritanceClaim(new InheritanceClaimRequest(
                "I6-CLAIM", "I6", "继承人己", TODAY.minusDays(30), 300L, 0L, 300L));
        InheritanceClaimResponse replay = pensionService.applyInheritanceClaim(new InheritanceClaimRequest(
                "I6-CLAIM", "I6", "继承人己", TODAY.minusDays(30), 300L, 0L, 300L));
        assertThat(replay.claimNo()).isEqualTo(first.claimNo());
        assertThat(replay.replayed()).isTrue();
        assertThat(countWhere("SELECT COUNT(*) FROM inheritance_claim")).isEqualTo(1L);

        assertThatThrownBy(() -> pensionService.applyInheritanceClaim(new InheritanceClaimRequest(
                "I6-CLAIM", "I6", "另一位继承人", TODAY.minusDays(30), 300L, 0L, 300L)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    @DisplayName("多名继承人并发申领同一成员：仅唯一受理，其余 409")
    void concurrentClaimsOnlyOneAccepted() throws Exception {
        member("I7", "继承庚", LocalDate.of(2020, 1, 1), MemberStatus.RETIRED);
        contribution("I7", ContributionType.PERSONAL, 700L, LocalDate.of(2024, 1, 1), "2024-01");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<String> claimNos = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < THREADS; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    InheritanceClaimResponse claim = pensionService.applyInheritanceClaim(
                            new InheritanceClaimRequest("I7-CLAIM-" + idx, "I7", "继承人" + idx,
                                    TODAY.minusDays(30), 700L, 0L, 700L));
                    accepted.incrementAndGet();
                    claimNos.add(claim.claimNo());
                } catch (ApiException ex) {
                    if ("INHERITANCE_CLAIM_EXISTS".equals(ex.getCode())) {
                        conflict.incrementAndGet();
                    } else {
                        throw ex;
                    }
                }
                return null;
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(accepted.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(THREADS - 1);
        assertThat(claimNos).hasSize(1);
        assertThat(countWhere("SELECT COUNT(*) FROM inheritance_claim")).isEqualTo(1L);
    }

    @Test
    @DisplayName("继承支付完成为终态：重复完成幂等成功，出款流水唯一，台账勾稽可复算")
    void completeIsTerminalAndIdempotent() {
        member("I8", "继承辛", LocalDate.of(2020, 1, 1), MemberStatus.RETIRED);
        contribution("I8", ContributionType.PERSONAL, 1000L, LocalDate.of(2024, 1, 1), "2024-01");
        InheritanceClaimResponse claim = pensionService.applyInheritanceClaim(new InheritanceClaimRequest(
                "I8-CLAIM", "I8", "继承人辛", TODAY.minusDays(30), 1000L, 0L, 1000L));

        InheritanceCompleteResponse done = pensionService.completeInheritancePayout(claim.claimNo(), false);
        assertThat(done.success()).isTrue();
        assertThat(done.replayed()).isFalse();
        assertThat(done.status()).isEqualTo(ClaimStatus.PAID);
        assertThat(done.amountCents()).isEqualTo(1000L);
        assertThat(done.paidAt()).isNotNull();

        InheritanceCompleteResponse again = pensionService.completeInheritancePayout(claim.claimNo(), false);
        assertThat(again.success()).isTrue();
        assertThat(again.replayed()).isTrue();
        assertThat(again.paidAt()).isEqualTo(done.paidAt());

        // 出款台账唯一，且构成合计=继承支付额=出款条目（可复算）
        assertThat(countWhere(
                "SELECT COUNT(*) FROM inheritance_ledger WHERE entry_type = 'INHERITANCE_PAYOUT'"))
                .isEqualTo(1L);
        InheritanceClaimResponse refreshed = pensionService.getInheritanceClaim(claim.claimNo());
        assertThat(refreshed.status()).isEqualTo(ClaimStatus.PAID);
        long components = refreshed.ledger().stream()
                .filter(e -> !e.entryType().equals("INHERITANCE_PAYOUT"))
                .mapToLong(InheritanceClaimResponse.LedgerEntry::amountCents).sum();
        long payout = refreshed.ledger().stream()
                .filter(e -> e.entryType().equals("INHERITANCE_PAYOUT"))
                .mapToLong(InheritanceClaimResponse.LedgerEntry::amountCents).sum();
        assertThat(components).isEqualTo(1000L);
        assertThat(payout).isEqualTo(components);
    }

    @Test
    @DisplayName("出款失败可重试：失败不改变申领状态，重试成功后完成")
    void completeFailureRetryable() {
        member("I9", "继承壬", LocalDate.of(2020, 1, 1), MemberStatus.RETIRED);
        contribution("I9", ContributionType.PERSONAL, 200L, LocalDate.of(2024, 1, 1), "2024-01");
        InheritanceClaimResponse claim = pensionService.applyInheritanceClaim(new InheritanceClaimRequest(
                "I9-CLAIM", "I9", "继承人壬", TODAY.minusDays(30), 200L, 0L, 200L));

        InheritanceCompleteResponse failed = pensionService.completeInheritancePayout(claim.claimNo(), true);
        assertThat(failed.success()).isFalse();
        assertThat(failed.replayed()).isFalse();
        assertThat(pensionService.getInheritanceClaim(claim.claimNo()).status())
                .isEqualTo(ClaimStatus.ACCEPTED);
        assertThat(countWhere(
                "SELECT COUNT(*) FROM inheritance_ledger WHERE entry_type = 'INHERITANCE_PAYOUT'"))
                .isZero();

        InheritanceCompleteResponse retried = pensionService.completeInheritancePayout(claim.claimNo(), false);
        assertThat(retried.success()).isTrue();
        assertThat(retried.status()).isEqualTo(ClaimStatus.PAID);
    }

    @Test
    @DisplayName("完成后迟到缴费只记迟到事件并提示人工复核；完成前缴费记余额不构成事件")
    void lateContributionAfterCompletionRecorded() {
        member("I10", "继承癸", LocalDate.of(2020, 1, 1), MemberStatus.RETIRED);
        contribution("I10", ContributionType.PERSONAL, 400L, LocalDate.of(2024, 1, 1), "2024-01");
        InheritanceClaimResponse claim = pensionService.applyInheritanceClaim(new InheritanceClaimRequest(
                "I10-CLAIM", "I10", "继承人癸", TODAY.minusDays(30), 400L, 0L, 400L));

        // 受理后、完成前到账：不构成迟到事件
        contribution("I10", ContributionType.PERSONAL, 50L, LocalDate.of(2026, 9, 1), "2026-09");
        assertThat(pensionService.listLateContributionEvents("I10")).isEmpty();

        pensionService.completeInheritancePayout(claim.claimNo(), false);
        contribution("I10", ContributionType.PERSONAL, 150L, LocalDate.of(2026, 9, 20), "2026-09");
        contribution("I10", ContributionType.ENTERPRISE, 250L, LocalDate.of(2026, 9, 21), "2026-09");

        List<LateContributionEventView> events = pensionService.listLateContributionEvents("I10");
        assertThat(events).hasSize(2);
        assertThat(events.get(0).amountCents()).isEqualTo(150L);
        assertThat(events.get(0).claimNo()).isEqualTo(claim.claimNo());
        assertThat(events.get(0).note()).contains("人工复核");
        assertThat(events.get(1).amountCents()).isEqualTo(250L);
        // 继承支付额不受迟到缴费影响
        assertThat(pensionService.getInheritanceClaim(claim.claimNo()).claimAmountCents()).isEqualTo(400L);
    }

    private long countWhere(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }
}
