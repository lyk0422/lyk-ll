package com.example.starter.radio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.example.starter.radio.domain.ChangeOrderStatus;
import com.example.starter.radio.domain.DispensingStatus;
import com.example.starter.radio.domain.WithdrawalStatus;
import com.example.starter.radio.model.Dispensing;
import com.example.starter.radio.model.MotherLiquor;
import com.example.starter.radio.model.WithdrawalItem;
import com.example.starter.radio.model.WithdrawalOrder;
import com.example.starter.radio.repository.ChangeOrderRepository;
import com.example.starter.radio.repository.DispensingRepository;
import com.example.starter.radio.repository.MotherLiquorRepository;
import com.example.starter.radio.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 未发药整批撤回流程的 H2 事务/并发测试（合成数据，仅软件验证）。
 * SYN-1H 半衰期 3600s；母液 2200 Bq @ T0；处方 1000 Bq @ T0+1h，分装恰 +10% 合法（1100 Bq）。
 * 基线分装计算不扣母液库存，撤回批准后母液余额仍为初始 2200 Bq。
 */
class WithdrawalServiceTest extends IntegrationTestBase {

    private static final long ADMIN_1 = T0.getEpochSecond() + 3600L;
    private static final long NEW_ADMIN_LEGAL = T0.getEpochSecond() + 4500L;

    @Autowired
    private WithdrawalService withdrawals;

    @Autowired
    private DispensingService dispensings;

    @Autowired
    private ChangeOrderService changeOrders;

    @Autowired
    private CalibratorService calibratorService;

    @Autowired
    private RegistrationService registrations;

    @Autowired
    private MotherLiquorRepository motherLiquors;

    @Autowired
    private DispensingRepository dispensingRepo;

    @Autowired
    private ChangeOrderRepository changeOrderRepo;

    // ---------------------------------------------------------------------
    // 主流程
    // ---------------------------------------------------------------------

    @Test
    void createOnlyFreezesListPendingWithoutVoiding() {
        Fixture f = new Fixture(2);

        WithdrawalService.CreateResult result = withdrawals.create(unique("WD"), f.motherId, nowEpoch(),
                "整批撤回原因", f.items("原因"));

        assertThat(result.replayed()).isFalse();
        WithdrawalOrder order = result.order();
        assertThat(order.status()).isEqualTo(WithdrawalStatus.PENDING);
        assertThat(order.versionNo()).isEqualTo(1);
        assertThat(order.reason()).isEqualTo("整批撤回原因");

        List<WithdrawalItem> items = withdrawals.evidence(order.id()).items();
        assertThat(items).hasSize(2);
        assertThat(items).extracting(WithdrawalItem::frozenVersionNo).containsOnly(1);
        assertThat(items).extracting(WithdrawalItem::dispensingId)
                .containsExactlyInAnyOrder(f.dispensingId(0), f.dispensingId(1));
        // 申请不作废任何分装，母液余额不变（基线计算本就不扣库存）
        for (long id : f.allDispensingIds()) {
            assertThat(dispensingRepo.findDispensingById(id).orElseThrow().status())
                    .isEqualTo(DispensingStatus.CALCULATED);
        }
        assertThat(motherLiquors.findById(f.motherId).orElseThrow().remainingActivityBq()).isEqualTo(2200.0);
    }

    @Test
    void dualApprovalVoidsAllTerminatesCandidatesInvalidatesChecksAndKeepsEvidence() {
        Fixture f = new Fixture(2).checked();
        // 第一份挂一个时刻候选，批准撤回时应被终结
        long candidateId = changeOrders.submit(unique("CHG"), f.dispensingId(0), 1, 1, NEW_ADMIN_LEGAL)
                .order().id();

        long orderId = withdrawals.create(unique("WD"), f.motherId, nowEpoch(), "批次质量复核撤回",
                f.items("合成原因")).order().id();
        WithdrawalOrder afterFirst = withdrawals.approve(orderId, 1, "carol");
        assertThat(afterFirst.firstApprover()).isEqualTo("carol");
        assertThat(afterFirst.versionNo()).isEqualTo(2);
        assertThat(afterFirst.status()).isEqualTo(WithdrawalStatus.PENDING);

        WithdrawalOrder approved = withdrawals.approve(orderId, 2, "dave");
        assertThat(approved.status()).isEqualTo(WithdrawalStatus.APPROVED);
        assertThat(approved.secondApprover()).isEqualTo("dave");
        assertThat(approved.versionNo()).isEqualTo(3);

        // 全清单一起 VOID，核对人字段失效
        for (long id : f.allDispensingIds()) {
            Dispensing d = dispensingRepo.findDispensingById(id).orElseThrow();
            assertThat(d.status()).isEqualTo(DispensingStatus.VOID);
            assertThat(d.firstChecker()).isNull();
            assertThat(d.secondChecker()).isNull();
        }
        // 旧签认行保留（每份 2 条），旧版本证据保留
        assertThat(dispensingRepo.findChecks(f.dispensingId(0))).hasSize(2);
        assertThat(dispensingRepo.findChecks(f.dispensingId(1))).hasSize(2);
        // 挂起时刻候选被终结为 VOID，并记录候选终结证据
        assertThat(changeOrderRepo.findById(candidateId).orElseThrow().status())
                .isEqualTo(ChangeOrderStatus.VOID);
        List<WithdrawalItem> items = withdrawals.evidence(orderId).items();
        WithdrawalItem item0 = items.stream().filter(i -> i.dispensingId() == f.dispensingId(0)).findFirst().orElseThrow();
        WithdrawalItem item1 = items.stream().filter(i -> i.dispensingId() == f.dispensingId(1)).findFirst().orElseThrow();
        assertThat(item0.terminatedCandidateId()).isEqualTo(candidateId);
        assertThat(item1.terminatedCandidateId()).isNull();
        // 母液余额不变、未产生对账台账
        MotherLiquor mother = motherLiquors.findById(f.motherId).orElseThrow();
        assertThat(mother.remainingActivityBq()).isEqualTo(2200.0);
        assertThat(mother.remainingTimeEpoch()).isEqualTo(T0.getEpochSecond());
        Integer ledgers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_ledger l JOIN dispensing d ON l.dispensing_id = d.id"
                        + " WHERE d.mother_liquor_id = ?", Integer.class, f.motherId);
        assertThat(ledgers).isZero();
        // 批准链证据
        assertThat(withdrawals.evidence(orderId).approvals()).extracting("approver")
                .containsExactly("carol", "dave");
    }

    @Test
    void voidIsTerminalCannotDispenseCheckOrChangeAgain() {
        Fixture f = new Fixture(1).checked();
        long orderId = createOrder(f);
        withdrawals.approve(orderId, 1, "carol");
        withdrawals.approve(orderId, 2, "dave");
        long id = f.dispensingId(0);

        assertThatThrownBy(() -> dispensings.check(id, "erin"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> dispensings.dispense(unique("REQ"), id, 0.0, ADMIN_1))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> changeOrders.submit(unique("CHG"), id, 1, 1, NEW_ADMIN_LEGAL))
                .isInstanceOf(BusinessException.class);
    }

    // ---------------------------------------------------------------------
    // 最终事务重查：已发药 / 版本改换回滚
    // ---------------------------------------------------------------------

    @Test
    void dispensedBetweenApprovalsRollsBackFinalApprovalLeavesNoVoid() {
        // 单分装撤回（合法批次 1~10 份）：候选期间原版本合法发药，最终批准须整体不撤回
        Fixture f = new Fixture(1).checked();
        long dispensingId = f.dispensingId(0);
        long orderId = createOrder(f);
        withdrawals.approve(orderId, 1, "carol");

        // 原合法发药抢先：2200 = 1100 + 0 + 1100
        dispensings.dispense(unique("REQ"), dispensingId, 0.0, ADMIN_1);
        assertThat(dispensingRepo.findDispensingById(dispensingId).orElseThrow().status())
                .isEqualTo(DispensingStatus.DISPENSED);

        assertThatThrownBy(() -> withdrawals.approve(orderId, 2, "dave"))
                .isInstanceOf(BusinessException.class);
        WithdrawalOrder still = withdrawals.getOrder(orderId);
        assertThat(still.status()).isEqualTo(WithdrawalStatus.PENDING);
        assertThat(still.versionNo()).isEqualTo(2);
        assertThat(dispensingRepo.findDispensingById(dispensingId).orElseThrow().status())
                .isEqualTo(DispensingStatus.DISPENSED);
        // 母液已发药测量不被撤回改动
        assertThat(motherLiquors.findById(f.motherId).orElseThrow().remainingActivityBq()).isEqualTo(0.0);
    }

    @Test
    void versionChangedBetweenApprovalsRollsBackFinalApproval() {
        Fixture f = new Fixture(2);
        long orderId = createOrder(f);
        withdrawals.approve(orderId, 1, "carol");

        // 第二份的时刻候选在等待期间被双人批准生效，版本 1 -> 2
        long changeId = changeOrders.submit(unique("CHG"), f.dispensingId(1), 1, 1, NEW_ADMIN_LEGAL)
                .order().id();
        changeOrders.review(changeId, "erin");
        changeOrders.review(changeId, "frank");
        assertThat(dispensingRepo.findDispensingById(f.dispensingId(1)).orElseThrow().versionNo()).isEqualTo(2);

        assertThatThrownBy(() -> withdrawals.approve(orderId, 2, "dave"))
                .isInstanceOf(BusinessException.class);
        // 全部不撤回：无一分装 VOID
        assertThat(dispensingRepo.findDispensingById(f.dispensingId(0)).orElseThrow().status())
                .isEqualTo(DispensingStatus.CALCULATED);
        assertThat(dispensingRepo.findDispensingById(f.dispensingId(1)).orElseThrow().status())
                .isEqualTo(DispensingStatus.CALCULATED);
        assertThat(withdrawals.getOrder(orderId).status()).isEqualTo(WithdrawalStatus.PENDING);
    }

    // ---------------------------------------------------------------------
    // 申请整单拒绝（不先作废其他项、不占键）
    // ---------------------------------------------------------------------

    @Test
    void unknownDispensingRejectsWholeOrderWithoutVoidingOthers() {
        Fixture f = new Fixture(1);
        List<WithdrawalService.CreateItem> items = new ArrayList<>(f.items("原因"));
        items.add(new WithdrawalService.CreateItem(999_999L, 1, "不存在"));
        String key = unique("WD");

        assertThatThrownBy(() -> withdrawals.create(key, f.motherId, nowEpoch(), "整批", items))
                .isInstanceOf(BusinessException.class);
        assertThat(dispensingRepo.findDispensingById(f.dispensingId(0)).orElseThrow().status())
                .isEqualTo(DispensingStatus.CALCULATED);
        // 失败不占键：同键随后用合法清单可成功
        assertThat(withdrawals.create(key, f.motherId, nowEpoch(), "整批", f.items("原因")).order().status())
                .isEqualTo(WithdrawalStatus.PENDING);
    }

    @Test
    void nuclideMismatchRejectsWholeOrder() {
        Fixture f = new Fixture(1);
        // 另一母液（同核素）上的分装不属于指定母液
        long otherMother = registrations.registerMotherLiquor(unique("ML"), "SYN-1H", 2200.0, T0.getEpochSecond())
                .id();
        long otherRx = registrations.registerPrescription(unique("RX"), "SYN-1H", 1000.0, ADMIN_1).id();
        long otherDispensing = dispensings.calculate(unique("REQ"), otherMother, otherRx, f.calibratorId).id();

        List<WithdrawalService.CreateItem> items = List.of(
                new WithdrawalService.CreateItem(f.dispensingId(0), 1, "本母液"),
                new WithdrawalService.CreateItem(otherDispensing, 1, "外母液"));
        assertThatThrownBy(() -> withdrawals.create(unique("WD"), f.motherId, nowEpoch(), "混批", items))
                .isInstanceOf(BusinessException.class);
        assertThat(dispensingRepo.findDispensingById(otherDispensing).orElseThrow().status())
                .isEqualTo(DispensingStatus.CALCULATED);
    }

    @Test
    void rejectedOrDispensedDispensingRejectsWholeOrder() {
        Fixture rejectedFx = new Fixture(1);
        jdbc.update("UPDATE dispensing SET status = 'REJECTED' WHERE id = ?", rejectedFx.dispensingId(0));
        assertThatThrownBy(() -> createOrder(rejectedFx))
                .isInstanceOf(BusinessException.class);

        Fixture dispensedFx = new Fixture(1).checked();
        dispensings.dispense(unique("REQ"), dispensedFx.dispensingId(0), 0.0, ADMIN_1);
        assertThatThrownBy(() -> createOrder(dispensedFx))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void invalidListShapeRejected() {
        Fixture f = new Fixture(1);
        // 空清单
        assertThatThrownBy(() -> withdrawals.create(unique("WD"), f.motherId, nowEpoch(), "空", List.of()))
                .isInstanceOf(BusinessException.class);
        // 重复分装
        List<WithdrawalService.CreateItem> dup = List.of(
                new WithdrawalService.CreateItem(f.dispensingId(0), 1, "a"),
                new WithdrawalService.CreateItem(f.dispensingId(0), 1, "b"));
        assertThatThrownBy(() -> withdrawals.create(unique("WD"), f.motherId, nowEpoch(), "重复", dup))
                .isInstanceOf(BusinessException.class);
        // 超过 10 份
        Fixture big = new Fixture(10);
        assertThatThrownBy(() -> withdrawals.create(unique("WD"), big.motherId, nowEpoch(), "超限",
                big.plusOneItem()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void taskBelongsToAtMostOneActiveWithdrawalUntilReleased() {
        Fixture f = new Fixture(2);
        long first = createOrder(f);
        // 重叠占用：第二张活动撤回单引用其中一份即拒绝
        List<WithdrawalService.CreateItem> overlap = List.of(
                new WithdrawalService.CreateItem(f.dispensingId(0), 1, "再次占用"));
        assertThatThrownBy(() -> withdrawals.create(unique("WD"), f.motherId, nowEpoch(), "重叠", overlap))
                .isInstanceOf(BusinessException.class);

        // 撤销第一张释放占用后可重新入批
        withdrawals.cancel(unique("CANCEL"), first, 1);
        WithdrawalOrder second = withdrawals.create(unique("WD"), f.motherId, nowEpoch(), "重新",
                overlap).order();
        assertThat(second.status()).isEqualTo(WithdrawalStatus.PENDING);
    }

    // ---------------------------------------------------------------------
    // 双人批准 / 乐观版本
    // ---------------------------------------------------------------------

    @Test
    void approvalRequiresTwoDistinctApproversAndFreshVersion() {
        Fixture f = new Fixture(1);
        long orderId = createOrder(f);
        withdrawals.approve(orderId, 1, "carol");
        // 同一人再次批准 → 409
        assertThatThrownBy(() -> withdrawals.approve(orderId, 2, "carol"))
                .isInstanceOf(ConflictException.class);
        // 旧 expectedVersion（首批准后版本已为 2）→ 409
        assertThatThrownBy(() -> withdrawals.approve(orderId, 1, "dave"))
                .isInstanceOf(ConflictException.class);
        // 正确版本 + 不同人 → 成功
        assertThat(withdrawals.approve(orderId, 2, "dave").status())
                .isEqualTo(WithdrawalStatus.APPROVED);
        // 已批准不可再批准
        assertThatThrownBy(() -> withdrawals.approve(orderId, 3, "erin"))
                .isInstanceOf(BusinessException.class);
    }

    // ---------------------------------------------------------------------
    // 撤销
    // ---------------------------------------------------------------------

    @Test
    void cancelPendingIsIdempotentAndApprovedCannotCancel() {
        Fixture f = new Fixture(1);
        long orderId = createOrder(f);
        String cancelKey = unique("CANCEL");

        WithdrawalOrder cancelled = withdrawals.cancel(cancelKey, orderId, 1);
        assertThat(cancelled.status()).isEqualTo(WithdrawalStatus.VOID);
        assertThat(cancelled.versionNo()).isEqualTo(2);
        // 同键同参重放
        assertThat(withdrawals.cancel(cancelKey, orderId, 1).status()).isEqualTo(WithdrawalStatus.VOID);
        // 已终态再用新键撤销 → 422（状态非法，而非版本冲突）
        assertThatThrownBy(() -> withdrawals.cancel(unique("CANCEL"), orderId, 2))
                .isInstanceOf(BusinessException.class);

        // 仍 PENDING 的单携带旧 expectedVersion 撤销 → 409；正确版本可撤销
        Fixture h = new Fixture(1);
        long pendingId = createOrder(h);
        assertThatThrownBy(() -> withdrawals.cancel(unique("CANCEL"), pendingId, 2))
                .isInstanceOf(ConflictException.class);
        assertThat(withdrawals.cancel(unique("CANCEL"), pendingId, 1).status())
                .isEqualTo(WithdrawalStatus.VOID);

        // 已批准不能撤销
        Fixture g = new Fixture(1);
        long approvedId = createOrder(g);
        withdrawals.approve(approvedId, 1, "carol");
        withdrawals.approve(approvedId, 2, "dave");
        assertThatThrownBy(() -> withdrawals.cancel(unique("CANCEL"), approvedId, 3))
                .isInstanceOf(BusinessException.class);
    }

    // ---------------------------------------------------------------------
    // 申请幂等
    // ---------------------------------------------------------------------

    @Test
    void createIdempotentReplaySameParamsConflictDifferentParams() {
        Fixture f = new Fixture(2);
        String key = unique("WD");
        long clientTime = nowEpoch();
        List<WithdrawalService.CreateItem> items = f.items("原因");
        WithdrawalOrder first = withdrawals.create(key, f.motherId, clientTime, "整批", items).order();

        WithdrawalService.CreateResult replay = withdrawals.create(key, f.motherId, clientTime, "整批", items);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.order().id()).isEqualTo(first.id());

        // 不同 UTC 精确时刻 → 409
        assertThatThrownBy(() -> withdrawals.create(key, f.motherId, clientTime + 1, "整批", items))
                .isInstanceOf(ConflictException.class);
        // 不同原因 → 409
        assertThatThrownBy(() -> withdrawals.create(key, f.motherId, clientTime, "别的原因", items))
                .isInstanceOf(ConflictException.class);
        // 不同任务集合（同母液，仅取其中一份）→ 409
        List<WithdrawalService.CreateItem> subset = List.of(
                new WithdrawalService.CreateItem(f.dispensingId(0), 1, "原因"));
        assertThatThrownBy(() -> withdrawals.create(key, f.motherId, clientTime, "整批", subset))
                .isInstanceOf(ConflictException.class);
        // 同集合但某项版本期望不同 → 409（指纹不同；版本校验在幂等命中比较之后）
        List<WithdrawalService.CreateItem> changedVersion = List.of(
                new WithdrawalService.CreateItem(f.dispensingId(0), 1, "原因"),
                new WithdrawalService.CreateItem(f.dispensingId(1), 2, "原因"));
        assertThatThrownBy(() -> withdrawals.create(key, f.motherId, clientTime, "整批", changedVersion))
                .isInstanceOf(ConflictException.class);
    }

    // ---------------------------------------------------------------------
    // 并发：批准 vs 发药只能一方先成
    // ---------------------------------------------------------------------

    @Test
    void concurrentApproveAndDispenseOnlyOneWins() throws Exception {
        int approveWins = 0;
        int dispenseWins = 0;
        for (int run = 0; run < 5; run++) {
            Fixture f = new Fixture(1).checked();
            long orderId = createOrder(f);
            long dispensingId = f.dispensingId(0);
            withdrawals.approve(orderId, 1, "carol");

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            AtomicReference<Throwable> approveError = new AtomicReference<>();
            AtomicReference<Throwable> dispenseError = new AtomicReference<>();
            try {
                Future<?> approve = pool.submit(() -> {
                    await(start);
                    try {
                        withdrawals.approve(orderId, 2, "dave");
                    } catch (Throwable t) {
                        approveError.set(t);
                    }
                });
                Future<?> dispense = pool.submit(() -> {
                    await(start);
                    try {
                        dispensings.dispense(unique("REQ"), dispensingId, 0.0, ADMIN_1);
                    } catch (Throwable t) {
                        dispenseError.set(t);
                    }
                });
                start.countDown();
                approve.get(30, TimeUnit.SECONDS);
                dispense.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            boolean approved = withdrawals.getOrder(orderId).status() == WithdrawalStatus.APPROVED;
            DispensingStatus dispensingStatus = dispensingRepo.findDispensingById(dispensingId).orElseThrow()
                    .status();
            boolean dispensed = dispensingStatus == DispensingStatus.DISPENSED;
            boolean withdrawn = dispensingStatus == DispensingStatus.VOID;
            assertThat(approved ^ dispensed)
                    .as("run " + run + " approved=" + approved + " dispensed=" + dispensed)
                    .isTrue();
            assertThat(withdrawn).isEqualTo(approved);
            assertThat(approveError.get() == null || approveError.get() instanceof BusinessException).isTrue();
            assertThat(dispenseError.get() == null || dispenseError.get() instanceof BusinessException).isTrue();
            if (approved) {
                approveWins++;
            } else {
                dispenseWins++;
            }
        }
        assertThat(approveWins + dispenseWins).isEqualTo(5);
    }

    @Test
    void concurrentOverlappingWithdrawalCreatesExactlyOneActiveOrder() throws Exception {
        Fixture f = new Fixture(1);
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    await(start);
                    try {
                        withdrawals.create("WD-CONC-" + UUID.randomUUID() + "-" + idx, f.motherId, nowEpoch(),
                                "并发占用", f.items("原因"));
                        success.incrementAndGet();
                    } catch (BusinessException e) {
                        rejected.incrementAndGet();
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(success.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
        Integer activeFlags = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dispensing_withdrawal_item WHERE dispensing_id = ? AND active_order_flag = 1",
                Integer.class, f.dispensingId(0));
        assertThat(activeFlags).isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private long createOrder(Fixture f) {
        return withdrawals.create(unique("WD"), f.motherId, nowEpoch(), "整批撤回", f.items("原因")).order().id();
    }

    private long nowEpoch() {
        return clock.instant().getEpochSecond();
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 测试夹具：同一母液下 n 份已计算（合法）分装。 */
    private final class Fixture {
        private final long calibratorId;
        private final long motherId;
        private final List<Long> dispensingIds = new ArrayList<>();

        private Fixture(int n) {
            this.calibratorId = registrations.registerCalibrator(unique("CAL"), "合成活度计");
            calibratorService.dailyCheck(unique("REQ"), calibratorId, CHECK_DATE, 1000.0, 1000.0);
            this.motherId = registrations.registerMotherLiquor(unique("ML"), "SYN-1H", 2200.0, T0.getEpochSecond())
                    .id();
            for (int i = 0; i < n; i++) {
                long rx = registrations.registerPrescription(unique("RX"), "SYN-1H", 1000.0, ADMIN_1).id();
                Dispensing d = dispensings.calculate(unique("REQ"), motherId, rx, calibratorId);
                dispensingIds.add(d.id());
            }
        }

        private Fixture checked() {
            for (long id : dispensingIds) {
                dispensings.check(id, "alice");
                dispensings.check(id, "bob");
            }
            return this;
        }

        private long dispensingId(int i) {
            return dispensingIds.get(i);
        }

        private long[] allDispensingIds() {
            return dispensingIds.stream().mapToLong(Long::longValue).toArray();
        }

        private List<WithdrawalService.CreateItem> items(String reason) {
            return dispensingIds.stream()
                    .map(id -> new WithdrawalService.CreateItem(id, 1, reason))
                    .toList();
        }

        private List<WithdrawalService.CreateItem> plusOneItem() {
            List<WithdrawalService.CreateItem> all = new ArrayList<>(items("原因"));
            all.add(new WithdrawalService.CreateItem(999_999L, 1, "第11份"));
            return all;
        }
    }
}
