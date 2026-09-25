package com.example.starter.pension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.pension.domain.AccountEntryType;
import com.example.starter.pension.domain.AttemptResult;
import com.example.starter.pension.domain.ContributionSource;
import com.example.starter.pension.domain.EmploymentStatus;
import com.example.starter.pension.domain.InstallmentStatus;
import com.example.starter.pension.domain.InstallmentType;
import com.example.starter.pension.domain.PlanStatus;
import com.example.starter.pension.domain.PayoutReason;
import com.example.starter.pension.dto.ContributionRequest;
import com.example.starter.pension.dto.MemberRequest;
import com.example.starter.pension.dto.PaymentRequest;
import com.example.starter.pension.dto.PaymentResponse;
import com.example.starter.pension.dto.PlanResponse;
import com.example.starter.pension.dto.PayoutRequest;
import com.example.starter.pension.dto.SettleResponse;
import com.example.starter.pension.dto.SupplementRequest;
import com.example.starter.pension.repo.PlanRepository;
import com.example.starter.pension.service.ContributionService;
import com.example.starter.pension.service.MemberService;
import com.example.starter.pension.service.PayoutService;
import com.example.starter.pension.testsupport.PensionTestConfig;
import com.example.starter.pension.testsupport.PensionTestConfig.TransactionProbe;
import com.example.starter.pension.web.ConflictException;

/**
 * 基于 H2（MODE=MySQL）的端到端集成测试：真实数据库约束、事务与并发，
 * 不使用 mock 或内存 Map 替代数据库边界。固定时钟：2026-09-25（UTC）。
 */
@SpringBootTest
@Import(PensionTestConfig.class)
class PensionPayoutIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 25);

    @Autowired
    private MemberService memberService;
    @Autowired
    private ContributionService contributionService;
    @Autowired
    private PayoutService payoutService;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionProbe transactionProbe;

    @BeforeEach
    void cleanTables() {
        PensionTestConfig.cleanup(jdbc);
    }

    private void registerMember(String memberNo, LocalDate hireDate, EmploymentStatus status) {
        memberService.register(new MemberRequest(memberNo, "测试成员-" + memberNo, hireDate, status));
    }

    private void addContribution(String memberNo, ContributionSource source, long amountCents) {
        contributionService.register(new ContributionRequest(
                memberNo, TODAY.minusDays(30), source, amountCents));
    }

    private PlanResponse applyDefault(String requestId, String memberNo, InstallmentType type) {
        return payoutService.apply(new PayoutRequest(
                requestId, memberNo, PayoutReason.RESIGNED, type));
    }

    // ---------------- 司龄恰满档位与快照 ----------------

    @Test
    @DisplayName("司龄恰满5年按80%归属，个人缴费100%，受理时冻结快照，计划总额=归属快照")
    void exactFiveYearTierVestsEightyPercentAndSnapshotFrozen() {
        registerMember("M1", LocalDate.of(2021, 9, 25), EmploymentStatus.RESIGNED);
        addContribution("M1", ContributionSource.EMPLOYER, 10_000L);
        addContribution("M1", ContributionSource.EMPLOYEE, 4_000L);

        PlanResponse plan = applyDefault("REQ-1", "M1", InstallmentType.LUMP_SUM);

        assertThat(plan.tenureYears()).isEqualTo(5);
        assertThat(plan.vestingRateBp()).isEqualTo(8_000);
        assertThat(plan.employeeAmountCents()).isEqualTo(4_000L);
        assertThat(plan.employerVestedCents()).isEqualTo(8_000L);
        assertThat(plan.employerForfeitCents()).isEqualTo(2_000L);
        assertThat(plan.totalAmountCents()).isEqualTo(12_000L);
        assertThat(plan.status()).isEqualTo(PlanStatus.ACTIVE);

        // 受理后新增缴费不得改变已冻结快照与计划总额。
        addContribution("M1", ContributionSource.EMPLOYER, 100_000L);
        PlanResponse reloaded = payoutService.getPlan(plan.planNo());
        assertThat(reloaded.totalAmountCents()).isEqualTo(12_000L);
        assertThat(reloaded.employerVestedCents()).isEqualTo(8_000L);
    }

    @Test
    @DisplayName("司龄档位边界：恰满3年50%、不足3年0%、恰满8年100%")
    void tenureTierBoundaries() {
        registerMember("M3", LocalDate.of(2023, 9, 25), EmploymentStatus.RESIGNED);
        addContribution("M3", ContributionSource.EMPLOYER, 10_000L);
        assertThat(applyDefault("REQ-3", "M3", InstallmentType.LUMP_SUM).vestingRateBp())
                .isEqualTo(5_000);

        registerMember("M0", LocalDate.of(2024, 9, 25), EmploymentStatus.RETIRED);
        addContribution("M0", ContributionSource.EMPLOYER, 10_000L);
        PlanResponse zeroPlan = payoutService.apply(new PayoutRequest(
                "REQ-0", "M0", PayoutReason.RETIRED, InstallmentType.LUMP_SUM));
        assertThat(zeroPlan.tenureYears()).isEqualTo(2);
        assertThat(zeroPlan.vestingRateBp()).isZero();
        assertThat(zeroPlan.employerVestedCents()).isZero();
        assertThat(zeroPlan.employerForfeitCents()).isEqualTo(10_000L);

        registerMember("M8", LocalDate.of(2018, 9, 25), EmploymentStatus.RESIGNED);
        addContribution("M8", ContributionSource.EMPLOYER, 10_000L);
        assertThat(applyDefault("REQ-8", "M8", InstallmentType.LUMP_SUM).vestingRateBp())
                .isEqualTo(10_000);
    }

    // ---------------- 尾差 ----------------

    @Test
    @DisplayName("12期分期尾差并入最后一期：11×833 + 837 = 10000，月初到期")
    void installmentRemainderGoesToLastPayment() {
        registerMember("M2", LocalDate.of(2021, 9, 25), EmploymentStatus.RESIGNED);
        addContribution("M2", ContributionSource.EMPLOYEE, 10_000L);

        PlanResponse plan = applyDefault("REQ-2", "M2", InstallmentType.MONTHLY_12);

        assertThat(plan.installments()).hasSize(12);
        assertThat(plan.installments().subList(0, 11))
                .allMatch(i -> i.amountCents() == 833L && i.status() == InstallmentStatus.PENDING);
        assertThat(plan.installments().get(11).amountCents()).isEqualTo(837L);
        assertThat(plan.installments().stream().mapToLong(i -> i.amountCents()).sum())
                .isEqualTo(10_000L);
        // 各期按月初（UTC）到期：首期 2026-09-01，末期 2027-08-01。
        assertThat(plan.installments().get(0).dueDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(plan.installments().get(11).dueDate()).isEqualTo(LocalDate.of(2027, 8, 1));
    }

    // ---------------- 幂等重放与同键异参 ----------------

    @Test
    @DisplayName("支付申请同键同参返回首次结果（同一计划号），同键异参返回409且不产生新计划")
    void applyIdempotentReplayAndConflict() {
        registerMember("M4", LocalDate.of(2021, 9, 25), EmploymentStatus.RESIGNED);
        addContribution("M4", ContributionSource.EMPLOYEE, 5_000L);

        PlanResponse first = applyDefault("REQ-4", "M4", InstallmentType.LUMP_SUM);
        PlanResponse replay = applyDefault("REQ-4", "M4", InstallmentType.LUMP_SUM);
        assertThat(replay.planNo()).isEqualTo(first.planNo());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payout_plan", Integer.class)).isEqualTo(1);

        assertThatThrownBy(() -> payoutService.apply(new PayoutRequest(
                "REQ-4", "M4", PayoutReason.RESIGNED, InstallmentType.MONTHLY_12)))
                .isInstanceOf(ConflictException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payout_plan", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("每期支付指令同键重放返回首次结果；失败指令重放不重复出款")
    void paymentIdempotentReplay() {
        PlanResponse plan = settledReadyPlan("M5", "REQ-5", 10_000L);

        PaymentResponse ok = payoutService.pay(new PaymentRequest(
                "PAY-5", plan.planNo(), 1, false));
        PaymentResponse replay = payoutService.pay(new PaymentRequest(
                "PAY-5", plan.planNo(), 1, false));
        assertThat(replay.result()).isEqualTo(AttemptResult.SUCCESS);
        assertThat(replay.processedAtUtc()).isEqualTo(ok.processedAtUtc());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM account_entry WHERE entry_type = 'MEMBER_PAYOUT'",
                Integer.class)).isEqualTo(1);

        registerMember("M5B", LocalDate.of(2021, 9, 25), EmploymentStatus.RESIGNED);
        addContribution("M5B", ContributionSource.EMPLOYEE, 1_000L);
        PlanResponse planB = applyDefault("REQ-5B", "M5B", InstallmentType.LUMP_SUM);
        PaymentResponse failed = payoutService.pay(new PaymentRequest(
                "PAY-5B-F", planB.planNo(), 1, true));
        assertThat(failed.result()).isEqualTo(AttemptResult.FAILED);
        PaymentResponse failedReplay = payoutService.pay(new PaymentRequest(
                "PAY-5B-F", planB.planNo(), 1, true));
        assertThat(failedReplay.result()).isEqualTo(AttemptResult.FAILED);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_attempt WHERE request_id = 'PAY-5B-F'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("支付指令同键异参返回409且不产生新指令")
    void paymentSameKeyDifferentParamsConflict() {
        PlanResponse plan = settledReadyPlan("M6", "REQ-6", 10_000L);
        payoutService.pay(new PaymentRequest("PAY-6", plan.planNo(), 1, false));

        assertThatThrownBy(() -> payoutService.pay(new PaymentRequest(
                "PAY-6", plan.planNo(), 1, true)))
                .isInstanceOf(ConflictException.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_attempt WHERE request_id = 'PAY-6'",
                Integer.class)).isEqualTo(1);
    }

    // ---------------- 并发唯一受理 ----------------

    @Test
    @DisplayName("并发申请同一成员：唯一受理，一个成功一个409，缴费不重复归属")
    void concurrentApplyOnlyOneAccepted() throws Exception {
        registerMember("M7", LocalDate.of(2021, 9, 25), EmploymentStatus.RESIGNED);
        addContribution("M7", ContributionSource.EMPLOYER, 10_000L);
        addContribution("M7", ContributionSource.EMPLOYEE, 3_000L);

        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                final String requestId = "REQ-7-" + i;
                pool.submit(() -> {
                    try {
                        start.await();
                        applyDefault(requestId, "M7", InstallmentType.LUMP_SUM);
                        success.incrementAndGet();
                    } catch (ConflictException e) {
                        conflict.incrementAndGet();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payout_plan WHERE member_no = 'M7'", Integer.class)).isEqualTo(1);
        // 两笔缴费各只进入一个计划快照，未归属/归属金额不重复。
        assertThat(jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_cents),0) FROM plan_contribution WHERE plan_no IN "
                        + "(SELECT plan_no FROM payout_plan WHERE member_no = 'M7')",
                Long.class)).isEqualTo(13_000L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM contribution WHERE member_no = 'M7' AND used_by_plan_no IS NULL",
                Integer.class)).isZero();
    }

    // ---------------- 期次唯一出款 ----------------

    @Test
    @DisplayName("并发支付同一期：恰好一笔成功，另一笔409，成员只收到一次出款")
    void concurrentPaymentOnlyOnePayout() throws Exception {
        PlanResponse plan = settledReadyPlan("M8C", "REQ-8C", 5_000L);

        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                final String requestId = "PAY-8C-" + i;
                pool.submit(() -> {
                    try {
                        start.await();
                        PaymentResponse resp = payoutService.pay(new PaymentRequest(
                                requestId, plan.planNo(), 1, false));
                        if (resp.result() == AttemptResult.SUCCESS) {
                            success.incrementAndGet();
                        }
                    } catch (ConflictException e) {
                        conflict.incrementAndGet();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM account_entry WHERE plan_no = ? AND entry_type = 'MEMBER_PAYOUT'",
                Integer.class, plan.planNo())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_attempt WHERE plan_no = ? AND result = 'SUCCESS'",
                Integer.class, plan.planNo())).isEqualTo(1);
        assertThat(planRepository.findInstallment(plan.planNo(), 1).orElseThrow().status())
                .isEqualTo(InstallmentStatus.PAID);
    }

    // ---------------- 未归属划回原子性 ----------------

    @Test
    @DisplayName("末期出款事务失败整体回滚：成员出款、结清、划回均不落库")
    void settleAndCompanyReturnAtomicRollback() {
        registerMember("M9", LocalDate.of(2021, 9, 25), EmploymentStatus.RESIGNED);
        // 企业 10000 分，80% 归属，2000 分未归属须在结清时划回企业。
        addContribution("M9", ContributionSource.EMPLOYER, 10_000L);
        PlanResponse plan = applyDefault("REQ-9", "M9", InstallmentType.LUMP_SUM);

        assertThatThrownBy(() -> transactionProbe.payLastThenThrow(
                new PaymentRequest("PAY-9", plan.planNo(), 1, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("探针故意失败");

        assertThat(planRepository.findPlanByNo(plan.planNo()).orElseThrow().status())
                .isEqualTo(PlanStatus.ACTIVE);
        assertThat(planRepository.findInstallment(plan.planNo(), 1).orElseThrow().status())
                .isEqualTo(InstallmentStatus.PENDING);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM account_entry WHERE plan_no = ?",
                Integer.class, plan.planNo())).isZero();
        assertThat(planRepository.findAttemptByRequestId("PAY-9")).isEmpty();
        assertThat(planRepository.findIdempotency("PAY-9")).isEmpty();
    }

    @Test
    @DisplayName("正常结清：未归属企业缴费与末期成员出款同事务落库，划回总额=快照未归属额")
    void companyReturnedInSameTransactionAsLastPayout() {
        registerMember("M10", LocalDate.of(2021, 9, 25), EmploymentStatus.RESIGNED);
        addContribution("M10", ContributionSource.EMPLOYER, 10_001L);
        PlanResponse plan = applyDefault("REQ-10", "M10", InstallmentType.LUMP_SUM);
        // 80% 向下取整：归属 8000，未归属 2001（含不足1分的余数）。
        assertThat(plan.employerVestedCents()).isEqualTo(8_000L);
        assertThat(plan.employerForfeitCents()).isEqualTo(2_001L);

        PaymentResponse resp = payoutService.pay(new PaymentRequest(
                "PAY-10", plan.planNo(), 1, false));

        assertThat(resp.planSettled()).isTrue();
        assertThat(resp.planStatus()).isEqualTo(PlanStatus.SETTLED);
        assertThat(planRepository.sumAccountEntries(plan.planNo(), AccountEntryType.MEMBER_PAYOUT))
                .isEqualTo(8_000L);
        assertThat(planRepository.sumAccountEntries(plan.planNo(), AccountEntryType.COMPANY_RETURN))
                .isEqualTo(2_001L);
        // 结清与划回、出款在同一事务时刻完成。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(DISTINCT created_at_utc) FROM account_entry WHERE plan_no = ?",
                Integer.class, plan.planNo())).isEqualTo(1);
    }

    // ---------------- 结清终态 ----------------

    @Test
    @DisplayName("全部期次完成自动结清为终态，重复结清幂等成功，结清后不能再出款")
    void autoSettleTerminalAndIdempotentRepeatedSettle() {
        PlanResponse plan = settledReadyPlan("M11", "REQ-11", 1_000L);
        PaymentResponse resp = payoutService.pay(new PaymentRequest(
                "PAY-11", plan.planNo(), 1, false));
        assertThat(resp.planSettled()).isTrue();

        SettleResponse first = payoutService.settle(plan.planNo());
        assertThat(first.status()).isEqualTo(PlanStatus.SETTLED);
        assertThat(first.alreadySettled()).isTrue();
        SettleResponse second = payoutService.settle(plan.planNo());
        assertThat(second.alreadySettled()).isTrue();
        // 划回不重复（本快照无未归属额，恒为 0；此处断言台账笔数不增加）。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM account_entry WHERE plan_no = ? AND entry_type = 'COMPANY_RETURN'",
                Integer.class, plan.planNo())).isZero();

        assertThatThrownBy(() -> payoutService.pay(new PaymentRequest(
                "PAY-11-AGAIN", plan.planNo(), 1, false)))
                .isInstanceOf(ConflictException.class);
    }

    // ---------------- 失败重试 ----------------

    @Test
    @DisplayName("出款失败期次保持待支付，换新requestId可重试成功，最多出款一次")
    void failedPaymentCanRetryAndSucceedsOnce() {
        registerMember("M12", LocalDate.of(2021, 9, 25), EmploymentStatus.RESIGNED);
        addContribution("M12", ContributionSource.EMPLOYEE, 2_000L);
        PlanResponse plan = applyDefault("REQ-12", "M12", InstallmentType.LUMP_SUM);

        PaymentResponse failed = payoutService.pay(new PaymentRequest(
                "PAY-12-F", plan.planNo(), 1, true));
        assertThat(failed.result()).isEqualTo(AttemptResult.FAILED);
        assertThat(planRepository.findInstallment(plan.planNo(), 1).orElseThrow().status())
                .isEqualTo(InstallmentStatus.PENDING);
        assertThat(planRepository.sumAccountEntries(plan.planNo(), AccountEntryType.MEMBER_PAYOUT))
                .isZero();

        PaymentResponse success = payoutService.pay(new PaymentRequest(
                "PAY-12-R", plan.planNo(), 1, false));
        assertThat(success.result()).isEqualTo(AttemptResult.SUCCESS);
        assertThat(planRepository.findInstallment(plan.planNo(), 1).orElseThrow().status())
                .isEqualTo(InstallmentStatus.PAID);
        assertThat(planRepository.sumAccountEntries(plan.planNo(), AccountEntryType.MEMBER_PAYOUT))
                .isEqualTo(2_000L);
    }

    // ---------------- 补发引用 ----------------

    @Test
    @DisplayName("结清后补缴必须补发并引用原计划号，原计划不被改动")
    void supplementAfterSettlementReferencesOriginalPlan() {
        registerMember("M13", LocalDate.of(2021, 9, 25), EmploymentStatus.RESIGNED);
        addContribution("M13", ContributionSource.EMPLOYEE, 3_000L);
        PlanResponse original = applyDefault("REQ-13", "M13", InstallmentType.LUMP_SUM);
        payoutService.pay(new PaymentRequest("PAY-13", original.planNo(), 1, false));
        assertThat(payoutService.getPlan(original.planNo()).status()).isEqualTo(PlanStatus.SETTLED);

        // 结清后补登一笔历史缴费。
        addContribution("M13", ContributionSource.EMPLOYER, 10_000L);

        // 直接再发起普通支付被拒绝。
        assertThatThrownBy(() -> applyDefault("REQ-13-NEW", "M13", InstallmentType.LUMP_SUM))
                .isInstanceOf(com.example.starter.pension.web.BadRequestException.class);
        // 引用不存在的原计划报 404（此处以业务异常表达）。
        assertThatThrownBy(() -> payoutService.supplement(new SupplementRequest(
                "REQ-13-S0", "PL-NOT-EXIST", InstallmentType.LUMP_SUM)))
                .isInstanceOf(com.example.starter.pension.web.NotFoundException.class);

        PlanResponse supplement = payoutService.supplement(new SupplementRequest(
                "REQ-13-S1", original.planNo(), InstallmentType.MONTHLY_12));
        assertThat(supplement.originalPlanNo()).isEqualTo(original.planNo());
        assertThat(supplement.planNo()).isNotEqualTo(original.planNo());
        // 补发计划只含补缴部分：企业 10000 × 80% = 8000。
        assertThat(supplement.totalAmountCents()).isEqualTo(8_000L);
        assertThat(supplement.employerForfeitCents()).isEqualTo(2_000L);
        assertThat(supplement.installmentCount()).isEqualTo(12);

        // 原计划保持终态、总额不变，且不被补发流程改动。
        PlanResponse originalReloaded = payoutService.getPlan(original.planNo());
        assertThat(originalReloaded.status()).isEqualTo(PlanStatus.SETTLED);
        assertThat(originalReloaded.totalAmountCents()).isEqualTo(3_000L);
        assertThat(originalReloaded.installments()).hasSize(1);
        assertThat(originalReloaded.installments().get(0).status()).isEqualTo(InstallmentStatus.PAID);
        assertThat(originalReloaded.originalPlanNo()).isNull();
        assertThat(originalReloaded.settledAtUtc()).isNotNull();
    }

    /**
     * 构造“一次性、全部为个人缴费”的计划，便于测试单期出款。
     */
    private PlanResponse settledReadyPlan(String memberNo, String requestId, long amountCents) {
        registerMember(memberNo, LocalDate.of(2021, 9, 25), EmploymentStatus.RESIGNED);
        addContribution(memberNo, ContributionSource.EMPLOYEE, amountCents);
        return applyDefault(requestId, memberNo, InstallmentType.LUMP_SUM);
    }

    @Test
    @DisplayName("24期：各期按月初到期，末月为申请月+23，尾差并入末期，求和=总额")
    void monthly24DueDatesAndRemainder() {
        registerMember("M19", LocalDate.of(2018, 9, 25), EmploymentStatus.RETIRED);
        addContribution("M19", ContributionSource.EMPLOYEE, 10_000L);

        PlanResponse plan = payoutService.apply(new PayoutRequest(
                "REQ-19", "M19", PayoutReason.RETIRED, InstallmentType.MONTHLY_24));
        assertThat(plan.installments()).hasSize(24);
        assertThat(plan.installments().get(0).dueDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(plan.installments().get(23).dueDate()).isEqualTo(LocalDate.of(2028, 8, 1));
        // 10000 = 23×416(9568) + 432
        assertThat(plan.installments().subList(0, 23))
                .allMatch(i -> i.amountCents() == 416L);
        assertThat(plan.installments().get(23).amountCents()).isEqualTo(432L);
        assertThat(plan.installments().stream().mapToLong(i -> i.amountCents()).sum())
                .isEqualTo(10_000L);
    }

    // ---------------- 完整链路：归属→12期→逐期支付→自动结清 ----------------

    @Test
    @DisplayName("完整链路：12期逐期支付，前11期后仍进行中，末期后自动结清并划回未归属额")
    void fullLifecycleTwelveInstallmentsThenAutoSettle() {
        // 司龄恰满 3 年：企业缴费归属 50%。
        registerMember("M14", LocalDate.of(2023, 9, 25), EmploymentStatus.RESIGNED);
        addContribution("M14", ContributionSource.EMPLOYER, 10_000L);
        addContribution("M14", ContributionSource.EMPLOYEE, 4_000L);

        PlanResponse plan = payoutService.apply(new PayoutRequest(
                "REQ-14", "M14", PayoutReason.RESIGNED, InstallmentType.MONTHLY_12));
        // 个人 4000 + 企业归属 5000 = 9000；尾差：11×750=8250，末期 750。
        assertThat(plan.totalAmountCents()).isEqualTo(9_000L);
        assertThat(plan.employerForfeitCents()).isEqualTo(5_000L);

        for (int seq = 1; seq <= 11; seq++) {
            PaymentResponse resp = payoutService.pay(new PaymentRequest(
                    "PAY-14-" + seq, plan.planNo(), seq, false));
            assertThat(resp.result()).isEqualTo(AttemptResult.SUCCESS);
            assertThat(resp.planSettled()).isFalse();
            assertThat(resp.planStatus()).isEqualTo(PlanStatus.ACTIVE);
        }
        // 前 11 期完成后手动结清应被拒绝（尚有末期未付）。
        assertThatThrownBy(() -> payoutService.settle(plan.planNo()))
                .isInstanceOf(com.example.starter.pension.web.BadRequestException.class);

        PaymentResponse last = payoutService.pay(new PaymentRequest(
                "PAY-14-12", plan.planNo(), 12, false));
        assertThat(last.planSettled()).isTrue();
        assertThat(last.amountCents()).isEqualTo(750L);

        assertThat(planRepository.sumAccountEntries(plan.planNo(), AccountEntryType.MEMBER_PAYOUT))
                .isEqualTo(9_000L);
        assertThat(planRepository.sumAccountEntries(plan.planNo(), AccountEntryType.COMPANY_RETURN))
                .isEqualTo(5_000L);
        assertThat(planRepository.findPlanByNo(plan.planNo()).orElseThrow().status())
                .isEqualTo(PlanStatus.SETTLED);
        // 逐期成功出款尝试恰为 12 条。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_attempt WHERE plan_no = ? AND result = 'SUCCESS'",
                Integer.class, plan.planNo())).isEqualTo(12);
    }

    @Test
    @DisplayName("不足3年企业缴费零归属：计划总额仅含个人部分，结清时企业缴费全额划回")
    void zeroVestingAllEmployerContributionReturned() {
        registerMember("M15", LocalDate.of(2025, 9, 25), EmploymentStatus.RETIRED);
        addContribution("M15", ContributionSource.EMPLOYER, 7_777L);
        addContribution("M15", ContributionSource.EMPLOYEE, 1_000L);

        PlanResponse plan = payoutService.apply(new PayoutRequest(
                "REQ-15", "M15", PayoutReason.RETIRED, InstallmentType.LUMP_SUM));
        assertThat(plan.tenureYears()).isEqualTo(1);
        assertThat(plan.vestingRateBp()).isZero();
        assertThat(plan.employerVestedCents()).isZero();
        assertThat(plan.totalAmountCents()).isEqualTo(1_000L);

        PaymentResponse resp = payoutService.pay(new PaymentRequest(
                "PAY-15", plan.planNo(), 1, false));
        assertThat(resp.planSettled()).isTrue();
        assertThat(planRepository.sumAccountEntries(plan.planNo(), AccountEntryType.MEMBER_PAYOUT))
                .isEqualTo(1_000L);
        assertThat(planRepository.sumAccountEntries(plan.planNo(), AccountEntryType.COMPANY_RETURN))
                .isEqualTo(7_777L);
    }

    @Test
    @DisplayName("失败分支：在职成员不可申请、支付事由与状态不符返回400、无缴费不可建计划")
    void invalidApplicationsRejected() {
        registerMember("M16", LocalDate.of(2021, 9, 25), EmploymentStatus.ACTIVE);
        addContribution("M16", ContributionSource.EMPLOYEE, 1_000L);
        // 在职成员不可发起支付。
        assertThatThrownBy(() -> applyDefault("REQ-16", "M16", InstallmentType.LUMP_SUM))
                .isInstanceOf(com.example.starter.pension.web.BadRequestException.class);

        registerMember("M17", LocalDate.of(2021, 9, 25), EmploymentStatus.RESIGNED);
        // 已离职成员不能以退休事由申请。
        assertThatThrownBy(() -> payoutService.apply(new PayoutRequest(
                "REQ-17", "M17", PayoutReason.RETIRED, InstallmentType.LUMP_SUM)))
                .isInstanceOf(com.example.starter.pension.web.BadRequestException.class);
        // 无缴费记录不能创建计划。
        registerMember("M18", LocalDate.of(2021, 9, 25), EmploymentStatus.RESIGNED);
        assertThatThrownBy(() -> applyDefault("REQ-18", "M18", InstallmentType.LUMP_SUM))
                .isInstanceOf(com.example.starter.pension.web.BadRequestException.class);
    }
}
