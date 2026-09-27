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
import com.example.starter.radio.model.ChangeOrder;
import com.example.starter.radio.model.Dispensing;
import com.example.starter.radio.model.DispensingVersion;
import com.example.starter.radio.repository.ChangeOrderRepository;
import com.example.starter.radio.repository.DispensingRepository;
import com.example.starter.radio.repository.MotherLiquorRepository;
import com.example.starter.radio.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 给药时刻变更候选流程的 H2 事务/并发测试。
 * 合成数据：SYN-1H 半衰期 3600s；母液 2200 Bq @ T0；处方 1000 Bq。
 * 初始给药 T0+1h（活度 1100，偏差恰 +10%）；
 * 合法新时刻 T0+1.25h（活度约 924.986，偏差约 -7.5%）；
 * T0+2h 活度 550（偏差 -45%）超差拒绝。
 */
class ChangeOrderServiceTest extends IntegrationTestBase {

    private static final long ADMIN_1 = T0.getEpochSecond() + 3600L;
    private static final long NEW_ADMIN_LEGAL = T0.getEpochSecond() + 4500L;
    private static final long NEW_ADMIN_OVERSHOOT = T0.getEpochSecond() + 7200L;

    @Autowired
    private ChangeOrderService changeOrders;

    @Autowired
    private DispensingService dispensings;

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
    void submitCreatesCandidateWithoutChangingEffectiveDispensing() {
        Fixture f = newCalculatedFixture();

        ChangeOrderService.SubmitResult result = changeOrders.submit(unique("CHG"), f.dispensingId,
                1, 1, NEW_ADMIN_LEGAL);

        assertThat(result.replayed()).isFalse();
        ChangeOrder order = result.order();
        assertThat(order.status()).isEqualTo(ChangeOrderStatus.PENDING);
        assertThat(order.calcStatus()).isEqualTo("LEGAL");
        assertThat(order.candidateVersionNo()).isEqualTo(2);
        assertThat(order.candidateActivityBq()).isCloseTo(924.9860568, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(order.candidateDeviationRatio()).isCloseTo(-0.0750139, org.assertj.core.data.Offset.offset(1e-6));

        // 有效分装仍是旧版本：旧时刻/旧活度/版本 1，候选仅登记
        Dispensing effective = dispensingRepo.findDispensingById(f.dispensingId).orElseThrow();
        assertThat(effective.versionNo()).isEqualTo(1);
        assertThat(effective.dispensedActivityBq()).isEqualTo(1100.0);
        assertThat(effective.activeChangeOrderId()).isEqualTo(order.id());
    }

    @Test
    void approvalSwapsVersionAndClearsOldChecksButKeepsEvidence() {
        Fixture f = newCheckedFixture();
        long orderId = submitLegal(f.dispensingId);

        changeOrders.review(orderId, "carol");
        ChangeOrder approved = changeOrders.review(orderId, "dave");

        assertThat(approved.status()).isEqualTo(ChangeOrderStatus.APPROVED);
        assertThat(approved.secondReviewer()).isEqualTo("dave");

        Dispensing effective = dispensingRepo.findDispensingById(f.dispensingId).orElseThrow();
        // 同事务替换：新版本、新活度、状态回落待核对，旧双人核对有效性清除
        assertThat(effective.versionNo()).isEqualTo(2);
        assertThat(effective.status()).isEqualTo(DispensingStatus.CALCULATED);
        assertThat(effective.firstChecker()).isNull();
        assertThat(effective.secondChecker()).isNull();
        assertThat(effective.activeChangeOrderId()).isNull();
        assertThat(effective.dispensedActivityBq()).isCloseTo(924.9860568,
                org.assertj.core.data.Offset.offset(1e-6));
        // 旧签认行保留（v1 两条），但不再挂在有效分装字段上
        assertThat(dispensingRepo.findChecks(f.dispensingId)).hasSize(2);
        assertThat(dispensingRepo.findChecks(f.dispensingId).get(0).versionNo()).isEqualTo(1);

        // 版本证据：v1 初始（1100，无变更单）+ v2 变更（924.986，挂本变更单）
        List<DispensingVersion> versions = changeOrders.versions(f.dispensingId);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).versionNo()).isEqualTo(1);
        assertThat(versions.get(0).dispensedActivityBq()).isEqualTo(1100.0);
        assertThat(versions.get(0).changeOrderId()).isNull();
        assertThat(versions.get(1).versionNo()).isEqualTo(2);
        assertThat(versions.get(1).changeOrderId()).isEqualTo(orderId);
        assertThat(versions.get(1).administrationTimeEpoch()).isEqualTo(NEW_ADMIN_LEGAL);
    }

    @Test
    void afterApprovalOldVersionCannotDispenseAndNewVersionRequiresFreshDualCheck() {
        Fixture f = newCheckedFixture();
        long orderId = submitLegal(f.dispensingId);
        changeOrders.review(orderId, "carol");
        changeOrders.review(orderId, "dave");

        // 新版本待核对，旧版本发药拒绝（不能用新时刻搭旧活度）
        assertThatThrownBy(() -> dispensings.dispense(unique("REQ"), f.dispensingId, 0.0, NEW_ADMIN_LEGAL))
                .isInstanceOf(BusinessException.class);

        // 旧签认不得复用：必须按新版本重新两名不同人员核对
        dispensings.check(f.dispensingId, "alice");
        Dispensing afterSecond = dispensings.check(f.dispensingId, "bob");
        assertThat(afterSecond.status()).isEqualTo(DispensingStatus.CHECKED);
        // 新版本核对链新增 v2 两条，旧 v1 两条仍保留
        assertThat(dispensingRepo.findChecks(f.dispensingId)).hasSize(4);

        // 新版本按新时刻勾稽发药：2200 = 924.986 + 0 + 1275.014
        DispensingService.DispenseResult result = dispensings.dispense(unique("REQ"),
                f.dispensingId, 0.0, NEW_ADMIN_LEGAL);
        assertThat(result.dispensing().status()).isEqualTo(DispensingStatus.DISPENSED);
        assertThat(result.ledger().balanced()).isTrue();
    }

    @Test
    void originalVersionCanDispenseWhileCandidatePendingAndSupersedesCandidate() {
        Fixture f = newCheckedFixture();
        long orderId = submitLegal(f.dispensingId);

        // 候选期间原版本合法发药（v1，旧时刻 T0+1h）
        DispensingService.DispenseResult result = dispensings.dispense(unique("REQ"),
                f.dispensingId, 0.0, ADMIN_1);
        assertThat(result.dispensing().status()).isEqualTo(DispensingStatus.DISPENSED);

        ChangeOrder order = changeOrders.getOrder(orderId);
        assertThat(order.status()).isEqualTo(ChangeOrderStatus.SUPERSEDED);
        // 已发药历史不可重开：候选复核与再次提交均拒绝
        assertThatThrownBy(() -> changeOrders.review(orderId, "carol"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> changeOrders.submit(unique("CHG"), f.dispensingId, 1, 1, NEW_ADMIN_LEGAL))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void overshootCandidateRejectedWithoutInventoryDeduction() {
        Fixture f = newCalculatedFixture();
        ChangeOrder order = changeOrders.submit(unique("CHG"), f.dispensingId, 1, 1, NEW_ADMIN_OVERSHOOT).order();

        assertThat(order.status()).isEqualTo(ChangeOrderStatus.REJECTED);
        assertThat(order.calcStatus()).isEqualTo("REJECTED");
        assertThat(order.candidateActivityBq()).isNull();
        assertThat(order.candidateDeviationRatio()).isEqualTo(-0.45);
        // 不登记挂起候选、不扣减母液
        assertThat(dispensingRepo.findDispensingById(f.dispensingId).orElseThrow().activeChangeOrderId()).isNull();
        assertThat(motherLiquors.findById(f.motherId).orElseThrow().remainingActivityBq()).isEqualTo(2200.0);
        assertThatThrownBy(() -> changeOrders.review(order.id(), "carol"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void inventoryCheckFailureRejectsAndDoesNotDeduct() {
        Fixture f = newCalculatedFixture();
        // 人为把母液剩余活度降到 10 Bq（合法偏差仍通过，但库存不足）
        jdbc.update("UPDATE mother_liquor SET remaining_activity_bq = 10.0, remaining_time_epoch = ? WHERE id = ?",
                T0.getEpochSecond(), f.motherId);

        ChangeOrder order = changeOrders.submit(unique("CHG"), f.dispensingId, 1, 1, NEW_ADMIN_LEGAL).order();
        assertThat(order.status()).isEqualTo(ChangeOrderStatus.REJECTED);
        assertThat(order.calcStatus()).isEqualTo("REJECTED");
        assertThat(order.candidateActivityBq()).isNull();
        // 库存校验失败不扣减母液
        assertThat(motherLiquors.findById(f.motherId).orElseThrow().remainingActivityBq()).isEqualTo(10.0);
    }

    @Test
    void successfulReplayUnaffectedByLaterInventoryChange() {
        Fixture f = newCalculatedFixture();
        String requestId = unique("CHG");
        ChangeOrder first = changeOrders.submit(requestId, f.dispensingId, 1, 1, NEW_ADMIN_LEGAL).order();

        // 随后母液剩余量被其它发药大幅改变
        jdbc.update("UPDATE mother_liquor SET remaining_activity_bq = 1.0, remaining_time_epoch = ? WHERE id = ?",
                T0.getEpochSecond(), f.motherId);

        ChangeOrderService.SubmitResult replay = changeOrders.submit(requestId, f.dispensingId,
                1, 1, NEW_ADMIN_LEGAL);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.order().id()).isEqualTo(first.id());
        // 重放首次冻结结果，不重算
        assertThat(replay.order().candidateActivityBq()).isEqualTo(first.candidateActivityBq());
        assertThat(replay.order().status()).isEqualTo(ChangeOrderStatus.PENDING);
    }

    @Test
    void submitIdempotencySameKeyDifferentParamsConflicts() {
        Fixture f = newCalculatedFixture();
        String requestId = unique("CHG");
        ChangeOrder first = changeOrders.submit(requestId, f.dispensingId, 1, 1, NEW_ADMIN_LEGAL).order();
        ChangeOrderService.SubmitResult replay = changeOrders.submit(requestId, f.dispensingId,
                1, 1, NEW_ADMIN_LEGAL);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.order().id()).isEqualTo(first.id());

        assertThatThrownBy(() -> changeOrders.submit(requestId, f.dispensingId, 1, 1, NEW_ADMIN_OVERSHOOT))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void failedSubmitDoesNotOccupyKey() {
        Fixture f = newCalculatedFixture();
        String requestId = unique("CHG");
        // 新时刻不晚于母液参考时刻 → 422
        assertThatThrownBy(() -> changeOrders.submit(requestId, f.dispensingId, 1, 1, T0.getEpochSecond()))
                .isInstanceOf(BusinessException.class);
        // 同键随后用合法参数可成功，失败不占键
        ChangeOrder order = changeOrders.submit(requestId, f.dispensingId, 1, 1, NEW_ADMIN_LEGAL).order();
        assertThat(order.status()).isEqualTo(ChangeOrderStatus.PENDING);
    }

    @Test
    void staleExpectedVersionConflicts() {
        Fixture f = newCheckedFixture();
        long firstOrder = submitLegal(f.dispensingId);
        approve(firstOrder);

        // 生效后版本为 2：携带旧 expectedVersion=1 → 409
        assertThatThrownBy(() -> changeOrders.submit(unique("CHG"), f.dispensingId, 1, 2, NEW_ADMIN_LEGAL + 100L))
                .isInstanceOf(ConflictException.class);
        // 期望处方版本错误 → 409
        assertThatThrownBy(() -> changeOrders.submit(unique("CHG"), f.dispensingId, 2, 1, NEW_ADMIN_LEGAL + 100L))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void duplicatePendingCandidateRejected() {
        Fixture f = newCalculatedFixture();
        submitLegal(f.dispensingId);
        assertThatThrownBy(() -> changeOrders.submit(unique("CHG"), f.dispensingId, 1, 1, NEW_ADMIN_LEGAL + 100L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void reviewRequiresTwoDistinctReviewers() {
        Fixture f = newCheckedFixture();
        long orderId = submitLegal(f.dispensingId);
        ChangeOrder afterFirst = changeOrders.review(orderId, "carol");
        assertThat(afterFirst.firstReviewer()).isEqualTo("carol");
        assertThat(afterFirst.status()).isEqualTo(ChangeOrderStatus.PENDING);
        assertThatThrownBy(() -> changeOrders.review(orderId, "carol"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void voidOrderIsIdempotentAndClearsCandidate() {
        Fixture f = newCheckedFixture();
        long orderId = submitLegal(f.dispensingId);
        String voidKey = unique("VOID");

        ChangeOrder voided = changeOrders.voidOrder(voidKey, orderId);
        assertThat(voided.status()).isEqualTo(ChangeOrderStatus.VOID);
        assertThat(dispensingRepo.findDispensingById(f.dispensingId).orElseThrow().activeChangeOrderId()).isNull();

        // 同键同参重放
        assertThat(changeOrders.voidOrder(voidKey, orderId).status()).isEqualTo(ChangeOrderStatus.VOID);
        // 同键引用不同单 → 409
        assertThatThrownBy(() -> changeOrders.voidOrder(voidKey, orderId + 999_999L))
                .isInstanceOf(ConflictException.class);
        // 已作废不可再作废/复核
        assertThatThrownBy(() -> changeOrders.voidOrder(unique("VOID"), orderId))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> changeOrders.review(orderId, "carol"))
                .isInstanceOf(BusinessException.class);

        // 作废后原版本可正常发药
        DispensingService.DispenseResult result = dispensings.dispense(unique("REQ"),
                f.dispensingId, 0.0, ADMIN_1);
        assertThat(result.dispensing().status()).isEqualTo(DispensingStatus.DISPENSED);
    }

    @Test
    void evidenceQueryReturnsOrderAndReviewChain() {
        Fixture f = newCheckedFixture();
        long orderId = submitLegal(f.dispensingId);
        changeOrders.review(orderId, "carol");
        changeOrders.review(orderId, "dave");

        ChangeOrderService.ChangeEvidence evidence = changeOrders.evidence(orderId);
        assertThat(evidence.order().status()).isEqualTo(ChangeOrderStatus.APPROVED);
        assertThat(evidence.reviews()).extracting("reviewer").containsExactly("carol", "dave");
    }

    @Test
    void concurrentApproveAndDispenseOnlyOneWins() throws Exception {
        int approveWins = 0;
        int dispenseWins = 0;
        // 多次运行提升竞争覆盖
        for (int run = 0; run < 5; run++) {
            Fixture f = newCheckedFixture();
            long orderId = submitLegal(f.dispensingId);
            changeOrders.review(orderId, "carol");

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            AtomicReference<Throwable> approveError = new AtomicReference<>();
            AtomicReference<Throwable> dispenseError = new AtomicReference<>();
            try {
                Future<?> approve = pool.submit(() -> {
                    await(start);
                    try {
                        changeOrders.review(orderId, "dave");
                    } catch (Throwable t) {
                        approveError.set(t);
                    }
                });
                Future<?> dispense = pool.submit(() -> {
                    await(start);
                    try {
                        dispensings.dispense(unique("REQ"), f.dispensingId, 0.0, ADMIN_1);
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

            boolean approved = changeOrders.getOrder(orderId).status() == ChangeOrderStatus.APPROVED;
            boolean dispensed = dispensingRepo.findDispensingById(f.dispensingId).orElseThrow().status()
                    == DispensingStatus.DISPENSED;
            // 互斥：恰一方成功
            assertThat(approved ^ dispensed).as("run " + run + " approved=" + approved + " dispensed=" + dispensed)
                    .isTrue();
            assertThat(approveError.get() == null || approveError.get() instanceof BusinessException).isTrue();
            assertThat(dispenseError.get() == null || dispenseError.get() instanceof BusinessException).isTrue();
            if (approved) {
                approveWins++;
            } else {
                dispenseWins++;
                assertThat(changeOrders.getOrder(orderId).status()).isEqualTo(ChangeOrderStatus.SUPERSEDED);
            }
        }
        // 5 次中至少观测到两种结局之一即可（互斥性是核心断言）；记录分布
        assertThat(approveWins + dispenseWins).isEqualTo(5);
    }

    @Test
    void concurrentSameKeySubmitCreatesExactlyOneCandidate() throws Exception {
        Fixture f = newCalculatedFixture();
        String requestId = "CHG-CONC-" + UUID.randomUUID();
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Long>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return changeOrders.submit(requestId, f.dispensingId, 1, 1, NEW_ADMIN_LEGAL).order().id();
                }));
            }
            start.countDown();
            List<Long> ids = new ArrayList<>();
            for (Future<Long> future : futures) {
                ids.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(ids).containsOnly(ids.get(0));
        } finally {
            pool.shutdownNow();
        }
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dispensing_change_order WHERE request_id = ?", Integer.class, requestId);
        assertThat(rows).isEqualTo(1);
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long submitLegal(long dispensingId) {
        return changeOrders.submit(unique("CHG"), dispensingId, 1, 1, NEW_ADMIN_LEGAL).order().id();
    }

    private void approve(long orderId) {
        changeOrders.review(orderId, "carol");
        changeOrders.review(orderId, "dave");
    }

    private Fixture newCalculatedFixture() {
        long calibratorId = registrations.registerCalibrator(unique("CAL"), "合成活度计");
        calibratorService.dailyCheck(unique("REQ"), calibratorId, CHECK_DATE, 1000.0, 1000.0);
        long motherId = registrations.registerMotherLiquor(unique("ML"), "SYN-1H", 2200.0, T0.getEpochSecond())
                .id();
        long prescriptionId = registrations.registerPrescription(unique("RX"), "SYN-1H", 1000.0, ADMIN_1).id();
        Dispensing d = dispensings.calculate(unique("REQ"), motherId, prescriptionId, calibratorId);
        return new Fixture(motherId, prescriptionId, calibratorId, d.id());
    }

    private Fixture newCheckedFixture() {
        Fixture f = newCalculatedFixture();
        dispensings.check(f.dispensingId, "alice");
        dispensings.check(f.dispensingId, "bob");
        return f;
    }

    private record Fixture(long motherId, long prescriptionId, long calibratorId, long dispensingId) {
    }
}
