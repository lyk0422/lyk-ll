package com.example.starter.pension;

import com.example.starter.pension.api.InheritanceApplyRequest;
import com.example.starter.pension.api.InheritanceCompleteResponse;
import com.example.starter.pension.api.InheritanceResponse;
import com.example.starter.pension.api.PaymentInstructionRequest;
import com.example.starter.pension.api.PayoutRequest;
import com.example.starter.pension.api.PlanResponse;
import com.example.starter.pension.domain.ContributionType;
import com.example.starter.pension.domain.InheritanceStatus;
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
 * 继承申领 H2 集成测试：180 日窗口、金额勾稽、分期互斥、并发唯一受理、
 * 完成终态幂等与迟到缴费事件。
 */
class InheritanceIntegrationTest extends PensionIntegrationTestBase {

    private static final LocalDate DEATH_DATE = LocalDate.of(2026, 3, 1);
    private static final LocalDate DAY_180 = DEATH_DATE.plusDays(180);
    private static final LocalDate DAY_181 = DEATH_DATE.plusDays(181);

    @Test
    @DisplayName("主流程：进行中计划两条未付分期被终止，继承金额=账户余额+未付本金，勾稽可复算")
    void applyInheritanceTerminatesPlanAndReconciles() {
        member("H1", "继承甲", LocalDate.of(2018, 9, 25), MemberStatus.RETIRED);
        contribution("H1", ContributionType.PERSONAL, 2400L, LocalDate.of(2024, 1, 1), "2024-01");
        PlanResponse plan = pensionService.applyPayout(new PayoutRequest(
                "H1-REQ", "H1", PayoutReason.RETIRE, InstallmentType.MONTHLY_12, TODAY));
        assertThat(plan.vestedTotalCents()).isEqualTo(2400L);
        // 第 1 期已出款 200 分，剩余 11 期未付共 2200 分
        pensionService.payInstallment(new PaymentInstructionRequest("H1-PAY-1", plan.planNo(), 1, false));
        // 计划冻结后新到的缴费构成账户余额 500 分
        contribution("H1", ContributionType.PERSONAL, 500L, LocalDate.of(2026, 9, 1), "2026-09");

        InheritanceResponse claim = pensionService.applyInheritance(new InheritanceApplyRequest(
                "H1-INH", "H1", "继承人甲", DEATH_DATE, DAY_180, 2700L));

        assertThat(claim.status()).isEqualTo(InheritanceStatus.ACCEPTED);
        assertThat(claim.accountBalanceCents()).isEqualTo(500L);
        assertThat(claim.unpaidInstallmentCents()).isEqualTo(2200L);
        assertThat(claim.inheritanceAmountCents()).isEqualTo(2700L);
        assertThat(claim.terminatedPlanNo()).isEqualTo(plan.planNo());
        // 勾稽明细可复算：分组成汇总与快照一致，总额与继承支付额一致
        assertThat(sumRecon(claim.claimNo(), "ACCOUNT_BALANCE")).isEqualTo(500L);
        assertThat(sumRecon(claim.claimNo(), "UNPAID_INSTALLMENT")).isEqualTo(2200L);
        assertThat(sumRecon(claim.claimNo(), "ACCOUNT_BALANCE")
                + sumRecon(claim.claimNo(), "UNPAID_INSTALLMENT"))
                .isEqualTo(claim.inheritanceAmountCents());
        assertThat(claim.reconciliation()).hasSize(12);

        // 互斥：计划终止（终态），已支付期次不回溯，未付期次全部终止
        PlanResponse terminated = pensionService.getPlan(plan.planNo());
        assertThat(terminated.status()).isEqualTo(PlanStatus.TERMINATED);
        assertThat(terminated.installments().get(0).status()).isEqualTo("PAID");
        assertThat(terminated.installments().get(0).amountCents()).isEqualTo(200L);
        for (int i = 1; i < 12; i++) {
            assertThat(terminated.installments().get(i).status()).isEqualTo("TERMINATED");
        }
        // 已出款流水保持 200 分不变
        assertThat(sumLedger("MEMBER_PAYOUT")).isEqualTo(200L);
    }

    @Test
    @DisplayName("180 日窗口：恰第 180 日受理，第 181 日逾期拒绝，申请日早于身故日拒绝")
    void windowBoundary() {
        member("H2", "继承乙", LocalDate.of(2018, 9, 25), MemberStatus.RETIRED);
        contribution("H2", ContributionType.PERSONAL, 100L, LocalDate.of(2024, 1, 1), "2024-01");

        // 恰第 180 日：受理
        InheritanceResponse claim = pensionService.applyInheritance(new InheritanceApplyRequest(
                "H2-INH", "H2", "继承人乙", DEATH_DATE, DAY_180, 100L));
        assertThat(claim.status()).isEqualTo(InheritanceStatus.ACCEPTED);

        member("H3", "继承丙", LocalDate.of(2018, 9, 25), MemberStatus.RETIRED);
        contribution("H3", ContributionType.PERSONAL, 100L, LocalDate.of(2024, 1, 1), "2024-01");
        // 第 181 日：逾期拒绝
        assertThatThrownBy(() -> pensionService.applyInheritance(new InheritanceApplyRequest(
                "H3-INH", "H3", "继承人丙", DEATH_DATE, DAY_181, 100L)))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("INHERITANCE_WINDOW_EXPIRED"));
        // 申请日早于身故日：拒绝
        assertThatThrownBy(() -> pensionService.applyInheritance(new InheritanceApplyRequest(
                "H3-INH-2", "H3", "继承人丙", DEATH_DATE, DEATH_DATE.minusDays(1), 100L)))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("APPLY_BEFORE_DEATH"));
        // 拒绝后未产生任何申领
        assertThat(countWhere("SELECT COUNT(*) FROM inheritance_claim")).isEqualTo(1L);
    }

    @Test
    @DisplayName("勾稽不一致拒绝：申报额不等于账户余额+未付本金之和")
    void reconciliationMismatchRejected() {
        member("H4", "继承丁", LocalDate.of(2018, 9, 25), MemberStatus.RETIRED);
        contribution("H4", ContributionType.PERSONAL, 300L, LocalDate.of(2024, 1, 1), "2024-01");

        assertThatThrownBy(() -> pensionService.applyInheritance(new InheritanceApplyRequest(
                "H4-INH", "H4", "继承人丁", DEATH_DATE, DAY_180, 301L)))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("RECONCILIATION_MISMATCH"));
        assertThat(countWhere("SELECT COUNT(*) FROM inheritance_claim")).isZero();
    }

    @Test
    @DisplayName("幂等：同 requestId 同参返回首次结果，同键异参 409")
    void idempotencySameKey() {
        member("H5", "继承戊", LocalDate.of(2018, 9, 25), MemberStatus.RETIRED);
        contribution("H5", ContributionType.PERSONAL, 100L, LocalDate.of(2024, 1, 1), "2024-01");
        InheritanceResponse first = pensionService.applyInheritance(new InheritanceApplyRequest(
                "H5-INH", "H5", "继承人戊", DEATH_DATE, DAY_180, 100L));

        InheritanceResponse replay = pensionService.applyInheritance(new InheritanceApplyRequest(
                "H5-INH", "H5", "继承人戊", DEATH_DATE, DAY_180, 100L));
        assertThat(replay.claimNo()).isEqualTo(first.claimNo());
        assertThat(countWhere("SELECT COUNT(*) FROM inheritance_claim")).isEqualTo(1L);

        assertThatThrownBy(() -> pensionService.applyInheritance(new InheritanceApplyRequest(
                "H5-INH", "H5", "另一位继承人", DEATH_DATE, DAY_180, 100L)))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        assertThat(countWhere("SELECT COUNT(*) FROM inheritance_claim")).isEqualTo(1L);
    }

    @Test
    @DisplayName("互斥反向：已受理继承申领的成员不能再申请支付计划")
    void inheritanceBlocksNewPayoutPlan() {
        member("H6", "继承己", LocalDate.of(2018, 9, 25), MemberStatus.RETIRED);
        contribution("H6", ContributionType.PERSONAL, 100L, LocalDate.of(2024, 1, 1), "2024-01");
        pensionService.applyInheritance(new InheritanceApplyRequest(
                "H6-INH", "H6", "继承人己", DEATH_DATE, DAY_180, 100L));

        assertThatThrownBy(() -> pensionService.applyPayout(new PayoutRequest(
                "H6-REQ", "H6", PayoutReason.RETIRE, InstallmentType.LUMP_SUM, TODAY)))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("INHERITANCE_CLAIM_EXISTS"));
    }

    @Test
    @DisplayName("完成终态：出款入账后重复完成幂等成功，台账仅一条")
    void completeIsTerminalAndIdempotent() {
        member("H7", "继承庚", LocalDate.of(2018, 9, 25), MemberStatus.RETIRED);
        contribution("H7", ContributionType.PERSONAL, 700L, LocalDate.of(2024, 1, 1), "2024-01");
        InheritanceResponse claim = pensionService.applyInheritance(new InheritanceApplyRequest(
                "H7-INH", "H7", "继承人庚", DEATH_DATE, DAY_180, 700L));

        InheritanceCompleteResponse done = pensionService.completeInheritance(claim.claimNo());
        assertThat(done.status()).isEqualTo(InheritanceStatus.COMPLETED);
        assertThat(done.replayed()).isFalse();
        assertThat(done.paidTotalCents()).isEqualTo(700L);
        assertThat(done.completedAt()).isNotNull();

        InheritanceCompleteResponse again = pensionService.completeInheritance(claim.claimNo());
        assertThat(again.replayed()).isTrue();
        assertThat(again.paidTotalCents()).isEqualTo(700L);
        assertThat(countWhere("SELECT COUNT(*) FROM inheritance_ledger")).isEqualTo(1L);
        assertThat(countWhere(
                "SELECT COUNT(*) FROM inheritance_ledger WHERE amount_cents = 700")).isEqualTo(1L);
    }

    @Test
    @DisplayName("完成后迟到缴费：照常入账，只记迟到事件并待人工复核")
    void lateContributionAfterCompletionRecordedForReview() {
        member("H8", "继承辛", LocalDate.of(2018, 9, 25), MemberStatus.RETIRED);
        contribution("H8", ContributionType.PERSONAL, 100L, LocalDate.of(2024, 1, 1), "2024-01");
        InheritanceResponse claim = pensionService.applyInheritance(new InheritanceApplyRequest(
                "H8-INH", "H8", "继承人辛", DEATH_DATE, DAY_180, 100L));
        pensionService.completeInheritance(claim.claimNo());

        contribution("H8", ContributionType.ENTERPRISE, 600L, LocalDate.of(2026, 9, 20), "2026-09");

        assertThat(countWhere(
                "SELECT COUNT(*) FROM late_contribution_event WHERE review_status = 'PENDING'"))
                .isEqualTo(1L);
        assertThat(countWhere("SELECT COUNT(*) FROM contribution")).isEqualTo(2L);
        // 迟到缴费不改变已完成的继承支付额与台账
        InheritanceResponse after = pensionService.getInheritance(claim.claimNo());
        assertThat(after.inheritanceAmountCents()).isEqualTo(100L);
        assertThat(after.status()).isEqualTo(InheritanceStatus.COMPLETED);
        assertThat(countWhere("SELECT COUNT(*) FROM inheritance_ledger")).isEqualTo(1L);
    }

    @Test
    @DisplayName("并发：多名继承人就同一成员分别申请，仅唯一受理")
    void concurrentHeirsOnlyOneAccepted() throws Exception {
        member("H9", "继承壬", LocalDate.of(2018, 9, 25), MemberStatus.RETIRED);
        contribution("H9", ContributionType.PERSONAL, 100L, LocalDate.of(2024, 1, 1), "2024-01");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<String> acceptedClaimNos = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    InheritanceResponse claim = pensionService.applyInheritance(
                            new InheritanceApplyRequest("H9-INH-" + idx, "H9",
                                    "继承人" + idx, DEATH_DATE, DAY_180, 100L));
                    accepted.incrementAndGet();
                    acceptedClaimNos.add(claim.claimNo());
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
        assertThat(conflict.get()).isEqualTo(threads - 1);
        assertThat(acceptedClaimNos).hasSize(1);
        assertThat(countWhere("SELECT COUNT(*) FROM inheritance_claim")).isEqualTo(1L);
    }

    @Test
    @DisplayName("并发：同一 requestId 并发申领，全部返回首次的同一申领")
    void concurrentSameRequestIdReturnsSameClaim() throws Exception {
        member("HA", "继承癸", LocalDate.of(2018, 9, 25), MemberStatus.RETIRED);
        contribution("HA", ContributionType.PERSONAL, 100L, LocalDate.of(2024, 1, 1), "2024-01");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return pensionService.applyInheritance(new InheritanceApplyRequest(
                        "HA-INH", "HA", "继承人癸", DEATH_DATE, DAY_180, 100L)).claimNo();
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        String claimNo = null;
        for (Future<String> f : futures) {
            String no = f.get(30, TimeUnit.SECONDS);
            if (claimNo == null) {
                claimNo = no;
            }
            assertThat(no).isEqualTo(claimNo);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(countWhere("SELECT COUNT(*) FROM inheritance_claim")).isEqualTo(1L);
    }

    private long sumRecon(String claimNo, String component) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(r.amount_cents),0) FROM inheritance_recon r"
                        + " JOIN inheritance_claim c ON c.id = r.claim_id"
                        + " WHERE c.claim_no = ? AND r.component = ?",
                Long.class, claimNo, component);
        return value == null ? 0L : value;
    }

    private long sumLedger(String movementType) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_cents),0) FROM account_ledger WHERE movement_type = ?",
                Long.class, movementType);
        return value == null ? 0L : value;
    }

    private long countWhere(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }
}
