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

/**
 * 真实多线程并发 H2 测试：并发申请唯一受理与同期次唯一出款。
 */
class ConcurrencyIntegrationTest extends PensionIntegrationTestBase {

    private static final int THREADS = 8;

    @Test
    @DisplayName("同一成员并发不同 requestId 申请：仅唯一受理，其余冲突，无重复快照")
    void concurrentApplicationsOnlyOneAccepted() throws Exception {
        member("C1", "并发甲", LocalDate.of(2023, 9, 25), MemberStatus.RESIGNED);
        contribution("C1", ContributionType.PERSONAL, 50L, LocalDate.of(2024, 1, 1), "2024-01");
        contribution("C1", ContributionType.ENTERPRISE, 100L, LocalDate.of(2024, 1, 1), "2024-01");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<String> acceptedPlanNos = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < THREADS; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    PlanResponse plan = pensionService.applyPayout(new PayoutRequest(
                            "C1-REQ-" + idx, "C1", PayoutReason.RESIGN,
                            InstallmentType.LUMP_SUM, TODAY));
                    accepted.incrementAndGet();
                    acceptedPlanNos.add(plan.planNo());
                } catch (ApiException ex) {
                    if ("ACTIVE_PLAN_EXISTS".equals(ex.getCode())) {
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
        assertThat(acceptedPlanNos).hasSize(1);
        assertThat(countPlans()).isEqualTo(1L);
        // 缴费只被冻结到唯一一个计划一次，不存在重复计划引用
        assertThat(countWhere("SELECT COUNT(*) FROM contribution WHERE snapshot_plan_id IS NOT NULL"))
                .isEqualTo(2L);
        PlanResponse plan = pensionService.getPlan(acceptedPlanNos.get(0));
        assertThat(plan.vestedTotalCents()).isEqualTo(100L);
        assertThat(plan.unvestedReturnCents()).isEqualTo(50L);
    }

    @Test
    @DisplayName("同一 requestId 并发申请：全部返回首次的同一计划")
    void concurrentSameRequestIdReturnsSamePlan() throws Exception {
        member("C2", "并发乙", LocalDate.of(2018, 9, 25), MemberStatus.RETIRED);
        contribution("C2", ContributionType.PERSONAL, 100L, LocalDate.of(2024, 1, 1), "2024-01");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return pensionService.applyPayout(new PayoutRequest(
                        "C2-REQ", "C2", PayoutReason.RETIRE,
                        InstallmentType.LUMP_SUM, TODAY)).planNo();
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        String planNo = null;
        for (Future<String> f : futures) {
            String no = f.get(30, TimeUnit.SECONDS);
            if (planNo == null) {
                planNo = no;
            }
            assertThat(no).isEqualTo(planNo);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(countPlans()).isEqualTo(1L);
    }

    @Test
    @DisplayName("同期次并发不同指令出款：仅一次出款、企业划回仅一次、计划自动结清")
    void concurrentPaymentsOnlyOnePayout() throws Exception {
        member("C3", "并发丙", LocalDate.of(2023, 9, 25), MemberStatus.RESIGNED);
        contribution("C3", ContributionType.PERSONAL, 50L, LocalDate.of(2024, 1, 1), "2024-01");
        contribution("C3", ContributionType.ENTERPRISE, 100L, LocalDate.of(2024, 1, 1), "2024-01");
        PlanResponse plan = pensionService.applyPayout(new PayoutRequest(
                "C3-REQ", "C3", PayoutReason.RESIGN, InstallmentType.LUMP_SUM, TODAY));
        assertThat(plan.vestedTotalCents()).isEqualTo(100L);
        assertThat(plan.unvestedReturnCents()).isEqualTo(50L);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger paid = new AtomicInteger();
        AtomicInteger alreadyPaid = new AtomicInteger();
        List<PaymentResultResponse> successes = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < THREADS; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                startLatch.await();
                try {
                    PaymentResultResponse r = pensionService.payInstallment(
                            new PaymentInstructionRequest("C3-PAY-" + idx, plan.planNo(), 1, false));
                    assertThat(r.success()).isTrue();
                    paid.incrementAndGet();
                    successes.add(r);
                } catch (ApiException ex) {
                    if ("INSTALLMENT_ALREADY_PAID".equals(ex.getCode())) {
                        alreadyPaid.incrementAndGet();
                    } else {
                        throw ex;
                    }
                }
                return null;
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        startLatch.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(paid.get()).isEqualTo(1);
        assertThat(alreadyPaid.get()).isEqualTo(THREADS - 1);
        assertThat(successes).hasSize(1);
        assertThat(successes.get(0).replayed()).isFalse();
        assertThat(countLedger("MEMBER_PAYOUT")).isEqualTo(1L);
        assertThat(sumLedger("MEMBER_PAYOUT")).isEqualTo(100L);
        assertThat(countLedger("ENTERPRISE_RETURN")).isEqualTo(1L);
        assertThat(sumLedger("ENTERPRISE_RETURN")).isEqualTo(50L);
        assertThat(pensionService.getPlan(plan.planNo()).status()).isEqualTo(PlanStatus.SETTLED);
    }

    @Test
    @DisplayName("同一指令 requestId 并发出款：全部返回首次成功，仅一条出款流水")
    void concurrentSamePaymentRequestIdReplays() throws Exception {
        member("C4", "并发丁", LocalDate.of(2018, 9, 25), MemberStatus.RETIRED);
        contribution("C4", ContributionType.PERSONAL, 100L, LocalDate.of(2024, 1, 1), "2024-01");
        PlanResponse plan = pensionService.applyPayout(new PayoutRequest(
                "C4-REQ", "C4", PayoutReason.RETIRE, InstallmentType.LUMP_SUM, TODAY));

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<PaymentResultResponse>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                startLatch.await();
                return pensionService.payInstallment(new PaymentInstructionRequest(
                        "C4-PAY", plan.planNo(), 1, false));
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        startLatch.countDown();
        int replayed = 0;
        int first = 0;
        for (Future<PaymentResultResponse> f : futures) {
            PaymentResultResponse r = f.get(30, TimeUnit.SECONDS);
            assertThat(r.success()).isTrue();
            if (r.replayed()) {
                replayed++;
            } else {
                first++;
            }
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(first).isEqualTo(1);
        assertThat(replayed).isEqualTo(THREADS - 1);
        assertThat(countLedger("MEMBER_PAYOUT")).isEqualTo(1L);
        assertThat(sumLedger("MEMBER_PAYOUT")).isEqualTo(100L);
        assertThat(pensionService.getPlan(plan.planNo()).status()).isEqualTo(PlanStatus.SETTLED);
    }

    private long countPlans() {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payout_plan", Long.class);
        return value == null ? 0L : value;
    }

    private long countWhere(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
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
