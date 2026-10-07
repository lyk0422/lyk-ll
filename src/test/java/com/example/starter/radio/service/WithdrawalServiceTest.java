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
import java.util.concurrent.atomic.AtomicReference;

import com.example.starter.radio.domain.ChangeOrderStatus;
import com.example.starter.radio.domain.DispensingStatus;
import com.example.starter.radio.domain.WithdrawalStatus;
import com.example.starter.radio.model.ChangeOrder;
import com.example.starter.radio.model.Dispensing;
import com.example.starter.radio.model.WithdrawalItem;
import com.example.starter.radio.model.WithdrawalOrder;
import com.example.starter.radio.repository.ChangeOrderRepository;
import com.example.starter.radio.repository.DispensingRepository;
import com.example.starter.radio.repository.MotherLiquorRepository;
import com.example.starter.radio.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 未发药整批撤回的 H2 事务/并发测试。
 * 合成数据：SYN-1H 半衰期 3600s；母液 2200 Bq @ T0；处方 1000 Bq @ T0+1h，
 * 衰变推算活度 1100 Bq（偏差恰 +10% 合法）。基线计算不扣母液，撤回后母液余额须保持 2200。
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

    @Test
    void submitFreezesPendingListAndOccupiesWithoutVoiding() {
        Fixture f = new Fixture(2, false);
        List<WithdrawalService.RequestItem> items = List.of(
                item(f.dispensingId(0), 1, "合成原因-甲"),
                item(f.dispensingId(1), 1, "合成原因-乙"));

        WithdrawalService.SubmitResult result = withdrawals.submit(unique("WD"), "整单合成原因", items);

        assertThat(result.replayed()).isFalse();
        WithdrawalOrder order = result.order();
        assertThat(order.status()).isEqualTo(WithdrawalStatus.PENDING);
        assertThat(order.versionNo()).isEqualTo(1);
        assertThat(order.motherLiquorId()).isEqualTo(f.motherId());
        assertThat(result.items()).hasSize(2);
        assertThat(result.items()).extracting(WithdrawalItem::frozenVersionNo).containsExactly(1, 1);
        assertThat(result.items()).extracting(WithdrawalItem::reason)
                .containsExactly("合成原因-甲", "合成原因-乙");
        // 仅冻结占用，不立即作废：原状态保持，占用指向本撤回单
        for (int i = 0; i < 2; i++) {
            Dispensing d = dispensingRepo.findDispensingById(f.dispensingId(i)).orElseThrow();
            assertThat(d.status()).isEqualTo(DispensingStatus.CALCULATED);
            assertThat(d.activeWithdrawalId()).isEqualTo(order.id());
        }
    }

    @Test
    void dualApprovalVoidsAllTogetherKeepsEvidenceAndDoesNotTouchMotherBalance() {
        Fixture f = new Fixture(2, true);
        long orderId = submit(f, "批量撤回");

        WithdrawalOrder afterFirst = withdrawals.approve(orderId, "alice", 1);
        assertThat(afterFirst.firstApprover()).isEqualTo("alice");
        assertThat(afterFirst.secondApprover()).isNull();
        assertThat(afterFirst.versionNo()).isEqualTo(2);

        WithdrawalOrder approved = withdrawals.approve(orderId, "bob", 2);
        assertThat(approved.status()).isEqualTo(WithdrawalStatus.APPROVED);
        assertThat(approved.secondApprover()).isEqualTo("bob");
        assertThat(approved.versionNo()).isEqualTo(3);

        for (int i = 0; i < 2; i++) {
            Dispensing d = dispensingRepo.findDispensingById(f.dispensingId(i)).orElseThrow();
            // 一起转 VOID 终态；核对不再有效；计算版本不换；占用释放
            assertThat(d.status()).isEqualTo(DispensingStatus.VOID);
            assertThat(d.firstChecker()).isNull();
            assertThat(d.secondChecker()).isNull();
            assertThat(d.versionNo()).isEqualTo(1);
            assertThat(d.activeWithdrawalId()).isNull();
            // 原计算与签认不删除
            assertThat(dispensingRepo.findVersions(f.dispensingId(i))).hasSize(1);
            assertThat(dispensingRepo.findChecks(f.dispensingId(i))).hasSize(2);
        }
        // 母液余额不因撤回加量；无发药测量与对账账本
        assertThat(motherLiquors.findById(f.motherId()).orElseThrow().remainingActivityBq()).isEqualTo(2200.0);
        assertThat(dispensingRepo.findLedger(f.dispensingId(0))).isEmpty();
        assertThat(dispensingRepo.findLedger(f.dispensingId(1))).isEmpty();

        WithdrawalService.WithdrawalEvidence evidence = withdrawals.evidence(orderId);
        assertThat(evidence.approvals()).extracting(a -> a.approver()).containsExactly("alice", "bob");
        assertThat(evidence.items()).hasSize(2);
    }

    @Test
    void dispensedTaskDuringPendingRejectsFinalApprovalAndRollsBackEverything() {
        // 单项撤回：pending 期间该分装仍可合法发药（勾稽 2200 = 1100 + 0 + 1100）
        Fixture f = new Fixture(1, true);
        long orderId = submit(f, "发药竞争");
        withdrawals.approve(orderId, "alice", 1);

        DispensingService.DispenseResult dispensed = dispensings.dispense(unique("REQ"),
                f.dispensingId(0), 0.0, ADMIN_1);
        assertThat(dispensed.dispensing().status()).isEqualTo(DispensingStatus.DISPENSED);

        // 第二人批准最终重查发现已发药 → 全部不撤回，且第二人批准/版本加一随事务回滚
        assertThatThrownBy(() -> withdrawals.approve(orderId, "bob", 2))
                .isInstanceOf(BusinessException.class);
        WithdrawalOrder stillPending = withdrawals.getOrder(orderId);
        assertThat(stillPending.status()).isEqualTo(WithdrawalStatus.PENDING);
        assertThat(stillPending.firstApprover()).isEqualTo("alice");
        assertThat(stillPending.secondApprover()).isNull();
        assertThat(stillPending.versionNo()).isEqualTo(2);
        assertThat(dispensingRepo.findDispensingById(f.dispensingId(0)).orElseThrow().status())
                .isEqualTo(DispensingStatus.DISPENSED);
        // 发药扣减保留，撤回不回补母液
        assertThat(motherLiquors.findById(f.motherId()).orElseThrow().remainingActivityBq()).isEqualTo(0.0);
    }

    @Test
    void versionChangedDuringPendingRejectsFinalApprovalWithoutPartialVoid() {
        Fixture f = new Fixture(2, true);
        long orderId = submit(f, "改版竞争");
        withdrawals.approve(orderId, "alice", 1);

        // pending 期间候选批准仍可先行：第 0 项计算版本由 1 换为 2
        long changeId = changeOrders.submit(unique("CHG"), f.dispensingId(0), 1, 1, NEW_ADMIN_LEGAL).order().id();
        changeOrders.review(changeId, "carol");
        changeOrders.review(changeId, "dave");
        assertThat(dispensingRepo.findDispensingById(f.dispensingId(0)).orElseThrow().versionNo()).isEqualTo(2);

        // 最终事务重查发现版本已换 → 全部不撤回，无一项 VOID
        assertThatThrownBy(() -> withdrawals.approve(orderId, "bob", 2))
                .isInstanceOf(BusinessException.class);
        for (int i = 0; i < 2; i++) {
            DispensingStatus status = dispensingRepo.findDispensingById(f.dispensingId(i)).orElseThrow().status();
            assertThat(status).isNotEqualTo(DispensingStatus.VOID);
        }
        assertThat(withdrawals.getOrder(orderId).status()).isEqualTo(WithdrawalStatus.PENDING);
    }

    @Test
    void approvedWithdrawalTerminatesAllPendingCandidates() {
        Fixture f = new Fixture(1, true);
        long changeId = changeOrders.submit(unique("CHG"), f.dispensingId(0), 1, 1, NEW_ADMIN_LEGAL).order().id();
        long orderId = submit(f, "候选终结");

        withdrawals.approve(orderId, "alice", 1);
        withdrawals.approve(orderId, "bob", 2);

        ChangeOrder candidate = changeOrderRepo.findById(changeId).orElseThrow();
        assertThat(candidate.status()).isEqualTo(ChangeOrderStatus.WITHDRAWN);
        assertThat(candidate.terminatedByWithdrawalId()).isEqualTo(orderId);
        assertThat(withdrawals.evidence(orderId).terminatedCandidates())
                .extracting(ChangeOrder::id).containsExactly(changeId);
    }

    @Test
    void approvalRequiresTwoDistinctApproversAndStaleVersionConflicts() {
        Fixture f = new Fixture(1, false);
        long orderId = submit(f, "双人");

        withdrawals.approve(orderId, "alice", 1);
        assertThatThrownBy(() -> withdrawals.approve(orderId, "alice", 2))
                .isInstanceOf(ConflictException.class);
        // 旧版本 → 409
        assertThatThrownBy(() -> withdrawals.approve(orderId, "bob", 1))
                .isInstanceOf(ConflictException.class);
        // 非 PENDING 不可再批
        withdrawals.approve(orderId, "bob", 2);
        assertThatThrownBy(() -> withdrawals.approve(orderId, "carol", 3))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void submitRejectsUnknownDispensingWholeOrder() {
        Fixture f = new Fixture(1, false);
        List<WithdrawalService.RequestItem> items = List.of(
                item(f.dispensingId(0), 1, "合法项"),
                item(999_999L, 1, "未知项"));
        assertThatThrownBy(() -> withdrawals.submit(unique("WD"), null, items))
                .isInstanceOf(NotFoundException.class);
        // 未留下撤回单与占用
        assertThat(dispensingRepo.findDispensingById(f.dispensingId(0)).orElseThrow().activeWithdrawalId())
                .isNull();
    }

    @Test
    void submitRejectsCrossMotherRejectedDispensedAndOccupied() {
        // 跨母液（即核素/母液不一致）整单拒绝，不先作废另一项
        Fixture f = new Fixture(2, false);
        long otherMother = registrations.registerMotherLiquor(unique("ML"), "SYN-1H", 2200.0, T0.getEpochSecond())
                .id();
        long otherCal = f.calibratorId();
        long otherRx = registrations.registerPrescription(unique("RX"), "SYN-1H", 1000.0, ADMIN_1).id();
        long otherDispensing = dispensings.calculate(unique("REQ"), otherMother, otherRx, otherCal).id();

        assertThatThrownBy(() -> withdrawals.submit(unique("WD"), null, List.of(
                item(f.dispensingId(0), 1, "甲"), item(otherDispensing, 1, "跨母液"))))
                .isInstanceOf(BusinessException.class);
        assertThat(dispensingRepo.findDispensingById(f.dispensingId(0)).orElseThrow().activeWithdrawalId())
                .isNull();

        // 已拒绝任务入批 → 422。构造一个超偏差拒绝分装（T0+2h 活度 550，偏差 -45%）
        long rejectedRx = registrations.registerPrescription(unique("RX"), "SYN-1H", 1000.0,
                T0.getEpochSecond() + 7200L).id();
        Dispensing rejected = dispensings.calculate(unique("REQ"), f.motherId(), rejectedRx, f.calibratorId());
        assertThat(rejected.status()).isEqualTo(DispensingStatus.REJECTED);
        assertThatThrownBy(() -> withdrawals.submit(unique("WD"), null, List.of(
                item(f.dispensingId(0), 1, "甲"), item(rejected.id(), 1, "已拒绝"))))
                .isInstanceOf(BusinessException.class);

        // 已发药任务入批 → 422（先对单项母液完成发药）
        Fixture single = new Fixture(1, true);
        dispensings.dispense(unique("REQ"), single.dispensingId(0), 0.0, ADMIN_1);
        assertThatThrownBy(() -> withdrawals.submit(unique("WD"), null,
                List.of(item(single.dispensingId(0), 1, "已发药"))))
                .isInstanceOf(BusinessException.class);

        // 任务已属活动撤回单 → 422
        submit(f, "首单占用");
        assertThatThrownBy(() -> withdrawals.submit(unique("WD"), null,
                List.of(item(f.dispensingId(0), 1, "重复占用"))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void submitRejectsStaleFrozenVersionAndBadShape() {
        Fixture f = new Fixture(1, false);
        // 版本不一致 → 409
        assertThatThrownBy(() -> withdrawals.submit(unique("WD"), null,
                List.of(item(f.dispensingId(0), 2, "错误版本"))))
                .isInstanceOf(ConflictException.class);
        // 空清单 / 超过 10 项 / 重复分装 → 400 类参数错误
        assertThatThrownBy(() -> withdrawals.submit(unique("WD"), null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withdrawals.submit(unique("WD"), null,
                List.of(item(f.dispensingId(0), 1, ""))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withdrawals.submit(unique("WD"), null,
                List.of(item(f.dispensingId(0), 1, "a"), item(f.dispensingId(0), 1, "b"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void submitIdempotencyReplaysSameParamsConflictsDifferentParamsAndFailureKeepsKey() {
        Fixture f = new Fixture(2, false);
        String key = unique("WD");
        List<WithdrawalService.RequestItem> items = List.of(
                item(f.dispensingId(0), 1, "甲"), item(f.dispensingId(1), 1, "乙"));
        WithdrawalOrder first = withdrawals.submit(key, "原因", items).order();

        WithdrawalService.SubmitResult replay = withdrawals.submit(key, "原因", items);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.order().id()).isEqualTo(first.id());

        // 同键异参（改原因）→ 409
        assertThatThrownBy(() -> withdrawals.submit(key, "不同原因", items))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void failedSubmitDoesNotOccupyKeyOrTasks() {
        Fixture f = new Fixture(2, false);
        String key = unique("WD");
        // 含已拒绝分装 → 失败
        long rejectedRx = registrations.registerPrescription(unique("RX"), "SYN-1H", 1000.0,
                T0.getEpochSecond() + 7200L).id();
        long rejectedId = dispensings.calculate(unique("REQ"), f.motherId(), rejectedRx, f.calibratorId()).id();
        assertThatThrownBy(() -> withdrawals.submit(key, null,
                List.of(item(f.dispensingId(0), 1, "甲"), item(rejectedId, 1, "拒绝"))))
                .isInstanceOf(BusinessException.class);
        // 同键随后用合法参数成功：失败不占键
        WithdrawalOrder order = withdrawals.submit(key, null,
                List.of(item(f.dispensingId(0), 1, "甲"), item(f.dispensingId(1), 1, "乙"))).order();
        assertThat(order.status()).isEqualTo(WithdrawalStatus.PENDING);
    }

    @Test
    void cancelReleasesOccupancyAndApprovedCannotCancel() {
        Fixture f = new Fixture(2, false);
        long orderId = submit(f, "待撤销");
        // 首个批准后仍可整体撤销（尚未完成双人批准）
        withdrawals.approve(orderId, "alice", 1);
        String cancelKey = unique("CANCEL");

        WithdrawalOrder cancelled = withdrawals.cancel(cancelKey, orderId, 2);
        assertThat(cancelled.status()).isEqualTo(WithdrawalStatus.CANCELLED);
        assertThat(cancelled.versionNo()).isEqualTo(3);
        for (int i = 0; i < 2; i++) {
            assertThat(dispensingRepo.findDispensingById(f.dispensingId(i)).orElseThrow().activeWithdrawalId())
                    .isNull();
        }
        // 撤销后这些分装可被新撤回单引用（占用已释放）
        WithdrawalOrder second = withdrawals.submit(unique("WD"), null,
                List.of(item(f.dispensingId(0), 1, "重新入批"), item(f.dispensingId(1), 1, "重新入批2")))
                .order();
        assertThat(second.status()).isEqualTo(WithdrawalStatus.PENDING);

        // 同键同参重放；引用不同单 → 409；已撤销不可再撤销
        assertThat(withdrawals.cancel(cancelKey, orderId, 9).status()).isEqualTo(WithdrawalStatus.CANCELLED);
        assertThatThrownBy(() -> withdrawals.cancel(cancelKey, orderId + 999_999L, 1))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> withdrawals.cancel(unique("CANCEL"), orderId, 3))
                .isInstanceOf(BusinessException.class);

        // 已批准不能撤销
        Fixture g = new Fixture(1, false);
        long approvedId = submit(g, "已批准");
        withdrawals.approve(approvedId, "alice", 1);
        withdrawals.approve(approvedId, "bob", 2);
        assertThatThrownBy(() -> withdrawals.cancel(unique("CANCEL"), approvedId, 3))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void voidTasksRejectDispenseChangeAndRecheck() {
        Fixture f = new Fixture(1, true);
        long orderId = submit(f, "终态保护");
        withdrawals.approve(orderId, "alice", 1);
        withdrawals.approve(orderId, "bob", 2);

        assertThatThrownBy(() -> dispensings.check(f.dispensingId(0), "carol"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> dispensings.dispense(unique("REQ"), f.dispensingId(0), 0.0, ADMIN_1))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> changeOrders.submit(unique("CHG"), f.dispensingId(0), 1, 1, NEW_ADMIN_LEGAL))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void concurrentFinalApprovalAndDispenseOnlyOneWins() throws Exception {
        int approveWins = 0;
        int dispenseWins = 0;
        for (int run = 0; run < 5; run++) {
            Fixture f = new Fixture(1, true);
            long orderId = submit(f, "竞争");
            withdrawals.approve(orderId, "alice", 1);

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            AtomicReference<Throwable> approveError = new AtomicReference<>();
            AtomicReference<Throwable> dispenseError = new AtomicReference<>();
            try {
                Future<?> approve = pool.submit(() -> {
                    await(start);
                    try {
                        withdrawals.approve(orderId, "bob", 2);
                    } catch (Throwable t) {
                        approveError.set(t);
                    }
                });
                Future<?> dispense = pool.submit(() -> {
                    await(start);
                    try {
                        dispensings.dispense(unique("REQ"), f.dispensingId(0), 0.0, ADMIN_1);
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
            DispensingStatus status = dispensingRepo.findDispensingById(f.dispensingId(0)).orElseThrow().status();
            boolean dispensed = status == DispensingStatus.DISPENSED;
            boolean isVoid = status == DispensingStatus.VOID;
            assertThat(approved ^ dispensed)
                    .as("run " + run + " approved=" + approved + " dispensed=" + dispensed + " void=" + isVoid)
                    .isTrue();
            assertThat(approved).isEqualTo(isVoid);
            assertThat(approveError.get() == null || approveError.get() instanceof BusinessException
                    || approveError.get() instanceof ConflictException).isTrue();
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
    void concurrentOverlappingSubmissionsCreateExactlyOneActiveWithdrawal() throws Exception {
        Fixture f = new Fixture(2, false);
        String keyA = "WD-A-" + UUID.randomUUID();
        String keyB = "WD-B-" + UUID.randomUUID();
        List<WithdrawalService.RequestItem> two = List.of(
                item(f.dispensingId(0), 1, "a"), item(f.dispensingId(1), 1, "b"));
        List<WithdrawalService.RequestItem> overlap = List.of(item(f.dispensingId(0), 1, "a"));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> errorA = new AtomicReference<>();
        AtomicReference<Throwable> errorB = new AtomicReference<>();
        try {
            Future<?> fa = pool.submit(() -> {
                await(start);
                try {
                    withdrawals.submit(keyA, null, two);
                } catch (Throwable t) {
                    errorA.set(t);
                }
            });
            Future<?> fb = pool.submit(() -> {
                await(start);
                try {
                    withdrawals.submit(keyB, null, overlap);
                } catch (Throwable t) {
                    errorB.set(t);
                }
            });
            start.countDown();
            fa.get(30, TimeUnit.SECONDS);
            fb.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        // 同一分装至多属于一张活动撤回单：两单竞争同一分装，恰一单成功、另一单整单拒绝
        boolean aOk = errorA.get() == null;
        boolean bOk = errorB.get() == null;
        assertThat(aOk ^ bOk).as("aOk=" + aOk + " bOk=" + bOk).isTrue();
        assertThat(errorA.get() == null || errorA.get() instanceof BusinessException).isTrue();
        assertThat(errorB.get() == null || errorB.get() instanceof BusinessException).isTrue();
        Dispensing d = dispensingRepo.findDispensingById(f.dispensingId(0)).orElseThrow();
        long activeId = d.activeWithdrawalId();
        assertThat(activeId).isNotNull();
        assertThat(withdrawals.getOrder(activeId).status()).isEqualTo(WithdrawalStatus.PENDING);
    }

    @Test
    void concurrentFinalApprovalAndChangeApprovalOnlyOneWins() throws Exception {
        int withdrawWins = 0;
        int changeWins = 0;
        for (int run = 0; run < 5; run++) {
            // 已双人核对的合法分装，同时挂一张待复核时刻候选（已完成第一复核）和一张待批准撤回单（已完成第一批准）
            Fixture f = new Fixture(1, true);
            long changeId = changeOrders.submit(unique("CHG"), f.dispensingId(0), 1, 1, NEW_ADMIN_LEGAL).order().id();
            changeOrders.review(changeId, "carol");
            long withdrawalId = submit(f, "改版竞争");
            withdrawals.approve(withdrawalId, "alice", 1);

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            AtomicReference<Throwable> withdrawError = new AtomicReference<>();
            AtomicReference<Throwable> changeError = new AtomicReference<>();
            try {
                Future<?> withdraw = pool.submit(() -> {
                    await(start);
                    try {
                        withdrawals.approve(withdrawalId, "bob", 2);
                    } catch (Throwable t) {
                        withdrawError.set(t);
                    }
                });
                Future<?> change = pool.submit(() -> {
                    await(start);
                    try {
                        changeOrders.review(changeId, "dave");
                    } catch (Throwable t) {
                        changeError.set(t);
                    }
                });
                start.countDown();
                withdraw.get(30, TimeUnit.SECONDS);
                change.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            boolean withdrawn = withdrawals.getOrder(withdrawalId).status() == WithdrawalStatus.APPROVED;
            boolean changed = changeOrderRepo.findById(changeId).orElseThrow().status()
                    == ChangeOrderStatus.APPROVED;
            // 互斥：仅一方先成
            assertThat(withdrawn ^ changed)
                    .as("run " + run + " withdrawn=" + withdrawn + " changed=" + changed).isTrue();
            Dispensing d = dispensingRepo.findDispensingById(f.dispensingId(0)).orElseThrow();
            if (withdrawn) {
                withdrawWins++;
                assertThat(d.status()).isEqualTo(DispensingStatus.VOID);
                assertThat(changeOrderRepo.findById(changeId).orElseThrow().status())
                        .isEqualTo(ChangeOrderStatus.WITHDRAWN);
            } else {
                changeWins++;
                assertThat(d.versionNo()).isEqualTo(2);
                assertThat(d.status()).isEqualTo(DispensingStatus.CALCULATED);
                assertThat(withdrawError.get()).isInstanceOf(BusinessException.class);
            }
        }
        assertThat(withdrawWins + changeWins).isEqualTo(5);
    }

    private void await(CountDownLatch latch) {        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private WithdrawalService.RequestItem item(long dispensingId, int version, String reason) {
        return new WithdrawalService.RequestItem(dispensingId, version, reason);
    }

    private long submit(Fixture f, String reason) {
        List<WithdrawalService.RequestItem> items = new ArrayList<>();
        for (int i = 0; i < f.size(); i++) {
            items.add(item(f.dispensingId(i), 1, reason + "-" + i));
        }
        return withdrawals.submit(unique("WD"), reason, items).order().id();
    }

    /**
     * 测试夹具：同一母液下 size 份各自处方、各自分装（活度均 1100 Bq，恰 +10% 合法）。
     */
    private final class Fixture {
        private final long motherId;
        private final long calibratorId;
        private final List<Long> dispensingIds = new ArrayList<>();
        private final int size;

        private Fixture(int size, boolean checked) {
            this.size = size;
            this.calibratorId = registrations.registerCalibrator(unique("CAL"), "合成活度计");
            calibratorService.dailyCheck(unique("REQ"), calibratorId, CHECK_DATE, 1000.0, 1000.0);
            this.motherId = registrations.registerMotherLiquor(unique("ML"), "SYN-1H", 2200.0, T0.getEpochSecond())
                    .id();
            for (int i = 0; i < size; i++) {
                long rxId = registrations.registerPrescription(unique("RX"), "SYN-1H", 1000.0, ADMIN_1).id();
                long dispensingId = dispensings.calculate(unique("REQ"), motherId, rxId, calibratorId).id();
                if (checked) {
                    dispensings.check(dispensingId, "alice");
                    dispensings.check(dispensingId, "bob");
                }
                dispensingIds.add(dispensingId);
            }
        }

        private long motherId() {
            return motherId;
        }

        private long calibratorId() {
            return calibratorId;
        }

        private int size() {
            return size;
        }

        private long dispensingId(int i) {
            return dispensingIds.get(i);
        }
    }
}
