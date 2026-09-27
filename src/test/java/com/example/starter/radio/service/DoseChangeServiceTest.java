package com.example.starter.radio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.example.starter.radio.domain.DispensingVersionStatus;
import com.example.starter.radio.domain.DoseChangeStatus;
import com.example.starter.radio.model.Dispensing;
import com.example.starter.radio.model.DispensingCheck;
import com.example.starter.radio.model.DispensingVersion;
import com.example.starter.radio.model.DoseChangeOrder;
import com.example.starter.radio.repository.MotherLiquorRepository;
import com.example.starter.radio.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 未发药分装给药时刻变更复核的 H2 集成与并发测试。
 * 合成数据：SYN-1H 半衰期 3600s；母液 2200 Bq @ T0；原给药 T0+1h 衰变后恰 1100 Bq（处方 1000，偏差恰 +10%）；
 * 新给药 T0+1h+300s 重算活度仍在 ±10% 内。
 */
class DoseChangeServiceTest extends IntegrationTestBase {

    private static final long ADMIN_EPOCH = T0.getEpochSecond() + 3600L;
    private static final long NEW_EPOCH = T0.getEpochSecond() + 3900L;

    @Autowired
    private DoseChangeService doseChangeService;

    @Autowired
    private DispensingService dispensingService;

    @Autowired
    private CalibratorService calibratorService;

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private MotherLiquorRepository motherLiquors;

    @Autowired
    private DecayService decay;

    private double expectedCandidateActivity() {
        return decay.activityAt(2200.0, T0.getEpochSecond(), NEW_EPOCH, 3600L);
    }

    @Test
    void submitCandidateDoesNotChangeEffectiveDispensing() {
        Fixture f = newCheckedFixture();
        DoseChangeService.ChangeOutcome outcome = doseChangeService.submit(
                unique("REQ-CHG"), f.dispensingId, 1, NEW_EPOCH);

        assertThat(outcome.phase()).isEqualTo("SUBMITTED");
        assertThat(outcome.replayed()).isFalse();
        assertThat(outcome.order().status()).isEqualTo(DoseChangeStatus.PENDING);
        assertThat(outcome.order().candidateVersion()).isEqualTo(2);
        assertThat(outcome.order().candidateActivityBq()).isEqualTo(expectedCandidateActivity());
        // 有效分装在候选期间保持原版本、原时刻、原活度与原双人核对状态
        Dispensing d = outcome.dispensing();
        assertThat(d.version()).isEqualTo(1);
        assertThat(d.administrationTimeEpoch()).isEqualTo(ADMIN_EPOCH);
        assertThat(d.dispensedActivityBq()).isEqualTo(1100.0);
        assertThat(d.status().name()).isEqualTo("CHECKED");
        // 母液不被扣减
        assertThat(motherLiquors.findById(f.motherId).orElseThrow().remainingActivityBq()).isEqualTo(2200.0);
    }

    @Test
    void twoDistinctReviewersApproveAndReplaceVersionAtomically() {
        Fixture f = newCheckedFixture();
        String submitReq = unique("REQ-CHG");
        DoseChangeOrder order = doseChangeService.submit(submitReq, f.dispensingId, 1, NEW_EPOCH).order();

        DoseChangeService.ChangeOutcome first = doseChangeService.review(unique("REQ-REV"), order.id(), "carol");
        assertThat(first.phase()).isEqualTo("FIRST_REVIEWED");
        assertThat(first.order().firstReviewer()).isEqualTo("carol");
        assertThat(first.dispensing().version()).isEqualTo(1);

        DoseChangeService.ChangeOutcome second = doseChangeService.review(unique("REQ-REV"), order.id(), "dave");
        assertThat(second.phase()).isEqualTo("APPROVED");
        // 生效：版本加 1、给药时刻与活度替换、状态回到待核对、旧双人核对有效性清空
        Dispensing d = second.dispensing();
        assertThat(d.version()).isEqualTo(2);
        assertThat(d.administrationTimeEpoch()).isEqualTo(NEW_EPOCH);
        assertThat(d.dispensedActivityBq()).isEqualTo(expectedCandidateActivity());
        assertThat(d.status().name()).isEqualTo("CALCULATED");
        assertThat(d.firstChecker()).isNull();
        assertThat(d.secondChecker()).isNull();

        // 旧版本与旧签认永久保留：v1 SUPERSEDED，version=1 的两条签认仍在
        List<DispensingVersion> versions = doseChangeService.versions(f.dispensingId);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).version()).isEqualTo(1);
        assertThat(versions.get(0).status()).isEqualTo(DispensingVersionStatus.SUPERSEDED);
        assertThat(versions.get(1).version()).isEqualTo(2);
        assertThat(versions.get(1).status()).isEqualTo(DispensingVersionStatus.ACTIVE);
        List<DispensingCheck> checks = dispensingServiceChecks(f.dispensingId);
        assertThat(checks).extracting(DispensingCheck::version).containsExactly(1, 1);
    }

    private List<DispensingCheck> dispensingServiceChecks(long dispensingId) {
        return jdbc.query("SELECT * FROM dispensing_check WHERE dispensing_id = ? ORDER BY version, check_seq",
                (rs, i) -> new DispensingCheck(rs.getLong(1), rs.getLong(2), rs.getInt(3),
                        rs.getString(4), rs.getInt(5), rs.getLong(6)),
                dispensingId);
    }

    @Test
    void afterApprovalDispenseRequiresFreshTwoPersonChecksAndUsesNewVersion() {
        Fixture f = approvedFixture();
        // 旧签认不得复用：未重新核对新版本不得发药
        assertThatThrownBy(() -> dispensingService.dispense(unique("REQ-DISP"), f.dispensingId, 0.0, NEW_EPOCH))
                .isInstanceOf(BusinessException.class);

        dispensingService.check(f.dispensingId, "alice");
        dispensingService.check(f.dispensingId, "bob");
        // 以新版本时刻与活度勾稽：2200 = A2 + 0 + (2200 - A2)
        DispensingService.DispenseResult result = dispensingService.dispense(
                unique("REQ-DISP"), f.dispensingId, 0.0, NEW_EPOCH);
        assertThat(result.dispensing().status().name()).isEqualTo("DISPENSED");
        assertThat(result.ledger().balanced()).isTrue();
        assertThat(result.ledger().dispensedTotalBq()).isEqualTo(expectedCandidateActivity());
    }

    @Test
    void sameReviewerTwiceConflicts() {
        Fixture f = newCheckedFixture();
        DoseChangeOrder order = doseChangeService.submit(unique("REQ-CHG"), f.dispensingId, 1, NEW_EPOCH).order();
        doseChangeService.review(unique("REQ-REV"), order.id(), "carol");
        assertThatThrownBy(() -> doseChangeService.review(unique("REQ-REV2"), order.id(), "carol"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void submittedChangeOnDispensedDispensingRejectedAndHistoryNeverReopens() {
        Fixture f = newCheckedFixture();
        dispensingService.dispense(unique("REQ-DISP"), f.dispensingId, 0.0, ADMIN_EPOCH);
        assertThatThrownBy(() -> doseChangeService.submit(unique("REQ-CHG"), f.dispensingId, 1, NEW_EPOCH))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已发药");
    }

    @Test
    void newAdministrationTimeMustBeAfterMotherReference() {
        Fixture f = newCheckedFixture();
        assertThatThrownBy(() -> doseChangeService.submit(
                unique("REQ-CHG"), f.dispensingId, 1, T0.getEpochSecond()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void candidateOutOfToleranceRejectedWithoutDeductingMotherLiquor() {
        // 处方 1200 Bq：原时刻活度 1100（偏差约 -8.3%）合法；新时刻重算约 1037 Bq（偏差约 -13.6%）超 ±10%
        Fixture f = newCheckedFixture(1200.0);
        assertThatThrownBy(() -> doseChangeService.submit(unique("REQ-CHG"), f.dispensingId, 1, NEW_EPOCH))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("偏差");
        assertThat(motherLiquors.findById(f.motherId).orElseThrow().remainingActivityBq()).isEqualTo(2200.0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dose_change_order", Integer.class)).isZero();
    }

    @Test
    void inventoryCheckFailureRejectsWithoutDeductingMotherLiquor() {
        Fixture f = newCheckedFixture();
        // 模拟母液已被其他批次消耗：剩余仅 500 Bq，无法支撑候选活度（约 1036 Bq）
        motherLiquors.updateRemaining(f.motherId, 500.0, T0.getEpochSecond());
        assertThatThrownBy(() -> doseChangeService.submit(unique("REQ-CHG"), f.dispensingId, 1, NEW_EPOCH))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("库存");
        assertThat(motherLiquors.findById(f.motherId).orElseThrow().remainingActivityBq()).isEqualTo(500.0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dose_change_order", Integer.class)).isZero();
    }

    @Test
    void staleExpectedVersionConflicts() {
        Fixture f = approvedFixture();
        // 分装已到版本 2，仍携带 expectedVersion=1 提交 → 409
        assertThatThrownBy(() -> doseChangeService.submit(unique("REQ-CHG"), f.dispensingId, 1,
                T0.getEpochSecond() + 4200L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("expectedVersion");
    }

    @Test
    void secondPendingCandidateConflictsAndCancelledAllowsResubmit() {
        Fixture f = newCheckedFixture();
        doseChangeService.submit(unique("REQ-CHG"), f.dispensingId, 1, NEW_EPOCH);
        assertThatThrownBy(() -> doseChangeService.submit(unique("REQ-CHG2"), f.dispensingId, 1,
                T0.getEpochSecond() + 4200L))
                .isInstanceOf(ConflictException.class);

        DoseChangeOrder first = doseChangeService.changeOrders(f.dispensingId).get(0);
        DoseChangeService.ChangeOutcome cancelled = doseChangeService.cancel(unique("REQ-CAN"), first.id());
        assertThat(cancelled.order().status()).isEqualTo(DoseChangeStatus.CANCELLED);
        // 作废后可提交新候选
        DoseChangeService.ChangeOutcome again = doseChangeService.submit(
                unique("REQ-CHG3"), f.dispensingId, 1, T0.getEpochSecond() + 4200L);
        assertThat(again.order().status()).isEqualTo(DoseChangeStatus.PENDING);
    }

    @Test
    void reviewOfCancelledCandidateRejected() {
        Fixture f = newCheckedFixture();
        DoseChangeOrder order = doseChangeService.submit(unique("REQ-CHG"), f.dispensingId, 1, NEW_EPOCH).order();
        doseChangeService.cancel(unique("REQ-CAN"), order.id());
        assertThatThrownBy(() -> doseChangeService.review(unique("REQ-REV"), order.id(), "carol"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void cancelOfApprovedCandidateRejected() {
        Fixture f = approvedFixture();
        DoseChangeOrder order = doseChangeService.changeOrders(f.dispensingId).get(0);
        assertThatThrownBy(() -> doseChangeService.cancel(unique("REQ-CAN"), order.id()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void unknownResourcesReturnNotFound() {
        assertThatThrownBy(() -> doseChangeService.submit(unique("REQ-CHG"), 999999L, 1, NEW_EPOCH))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> doseChangeService.review(unique("REQ-REV"), 999999L, "carol"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void submitIsIdempotentAndRejectsSameKeyDifferentParams() {
        Fixture f = newCheckedFixture();
        String requestId = unique("REQ-CHG");
        DoseChangeService.ChangeOutcome first = doseChangeService.submit(requestId, f.dispensingId, 1, NEW_EPOCH);
        DoseChangeService.ChangeOutcome replay = doseChangeService.submit(requestId, f.dispensingId, 1, NEW_EPOCH);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.phase()).isEqualTo("SUBMITTED");
        assertThat(replay.order().id()).isEqualTo(first.order().id());

        // 同键异参（新时刻不同）→ 409
        assertThatThrownBy(() -> doseChangeService.submit(requestId, f.dispensingId, 1,
                T0.getEpochSecond() + 4200L))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void failedSubmitDoesNotOccupyRequestId() {
        Fixture f = newCheckedFixture();
        String requestId = unique("REQ-CHG");
        // 首次因新时刻非法失败
        assertThatThrownBy(() -> doseChangeService.submit(requestId, f.dispensingId, 1, T0.getEpochSecond()))
                .isInstanceOf(IllegalArgumentException.class);
        // 同 requestId 合法提交应成功，说明失败未占键
        DoseChangeService.ChangeOutcome ok = doseChangeService.submit(requestId, f.dispensingId, 1, NEW_EPOCH);
        assertThat(ok.order().status()).isEqualTo(DoseChangeStatus.PENDING);
    }

    @Test
    void successfulReplayUnaffectedByLaterInventoryDrop() {
        Fixture f = newCheckedFixture();
        String requestId = unique("REQ-CHG");
        doseChangeService.submit(requestId, f.dispensingId, 1, NEW_EPOCH);
        // 随后母液被其他批次耗尽，剩余不足
        motherLiquors.updateRemaining(f.motherId, 1.0, T0.getEpochSecond());
        // 同键同参重放仍返回首次冻结的候选，不受库存变化影响
        DoseChangeService.ChangeOutcome replay = doseChangeService.submit(requestId, f.dispensingId, 1, NEW_EPOCH);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.order().status()).isEqualTo(DoseChangeStatus.PENDING);
        assertThat(replay.order().inventoryAvailableBq()).isGreaterThan(replay.order().candidateActivityBq() - 1e-9);
    }

    @Test
    void reviewIsIdempotentAndRejectsSameKeyDifferentParams() {
        Fixture f = newCheckedFixture();
        DoseChangeOrder order = doseChangeService.submit(unique("REQ-CHG"), f.dispensingId, 1, NEW_EPOCH).order();
        String reviewReq = unique("REQ-REV");
        doseChangeService.review(reviewReq, order.id(), "carol");
        DoseChangeService.ChangeOutcome replay = doseChangeService.review(reviewReq, order.id(), "carol");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.phase()).isEqualTo("FIRST_REVIEWED");
        // 同键异参（复核人不同）→ 409
        assertThatThrownBy(() -> doseChangeService.review(reviewReq, order.id(), "dave"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void dispenseWhileCandidatePendingSucceedsAndRejectsChangeOnSecondReview() {
        Fixture f = newCheckedFixture();
        DoseChangeOrder order = doseChangeService.submit(unique("REQ-CHG"), f.dispensingId, 1, NEW_EPOCH).order();
        // 候选待决期间原版本合法发药
        DispensingService.DispenseResult dispensed = dispensingService.dispense(
                unique("REQ-DISP"), f.dispensingId, 0.0, ADMIN_EPOCH);
        assertThat(dispensed.dispensing().status().name()).isEqualTo("DISPENSED");
        // 第二签：发药已先成，变更被拒绝，已发药历史不重开
        DoseChangeService.ChangeOutcome second = doseChangeService.review(unique("REQ-REV"), order.id(), "dave");
        assertThat(second.phase()).isEqualTo("REJECTED");
        assertThat(second.order().status()).isEqualTo(DoseChangeStatus.REJECTED);
        Dispensing d = dispensingService_viaId(f.dispensingId);
        assertThat(d.version()).isEqualTo(1);
        assertThat(d.status().name()).isEqualTo("DISPENSED");
    }

    @Test
    void approveAndDispenseRaceOnlyOneSideCommits() throws Exception {
        // 重复多轮以提高并发竞争被真实触发的概率
        for (int round = 0; round < 8; round++) {
            jdbc.update("DELETE FROM dose_change_check");
            jdbc.update("DELETE FROM dose_change_order");
            jdbc.update("DELETE FROM idempotent_request");
            jdbc.update("DELETE FROM reconciliation_ledger");
            jdbc.update("DELETE FROM dispensing_check");
            jdbc.update("DELETE FROM dispensing_version");
            jdbc.update("DELETE FROM dispensing");
            Fixture f = newCheckedFixture();
            DoseChangeOrder order = doseChangeService.submit(unique("REQ-CHG"), f.dispensingId, 1, NEW_EPOCH).order();
            doseChangeService.review(unique("REQ-REV"), order.id(), "carol");

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            String[] approvePhase = {null};
            Throwable[] approveError = {null};
            Throwable[] dispenseError = {null};
            try {
                pool.submit(() -> {
                    await(start);
                    try {
                        approvePhase[0] = doseChangeService
                                .review(unique("REQ-REV-APPROVE"), order.id(), "dave").phase();
                    } catch (Throwable t) {
                        approveError[0] = t;
                    }
                });
                pool.submit(() -> {
                    await(start);
                    try {
                        dispensingService.dispense(unique("REQ-DISP-RACE"), f.dispensingId, 0.0, ADMIN_EPOCH);
                    } catch (Throwable t) {
                        dispenseError[0] = t;
                    }
                });
                start.countDown();
            } finally {
                pool.shutdown();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            }

            Dispensing d = dispensingService_viaId(f.dispensingId);
            DoseChangeOrder finalOrder = doseChangeService.changeOrders(f.dispensingId).get(0);
            if (d.status().name().equals("DISPENSED")) {
                // 发药先成：版本保持 1，候选必被拒绝，且批准侧不应报错之外的不一致
                assertThat(d.version()).isEqualTo(1);
                assertThat(finalOrder.status()).isEqualTo(DoseChangeStatus.REJECTED);
                assertThat(approveError[0]).isNull();
                assertThat(approvePhase[0]).isEqualTo("REJECTED");
                assertThat(dispenseError[0]).isNull();
            } else {
                // 变更先成：版本升到 2、回到待核对，旧版本发药必已被业务拒绝
                assertThat(d.version()).isEqualTo(2);
                assertThat(d.status().name()).isEqualTo("CALCULATED");
                assertThat(finalOrder.status()).isEqualTo(DoseChangeStatus.APPROVED);
                assertThat(approvePhase[0]).isEqualTo("APPROVED");
                assertThat(dispenseError[0]).isInstanceOf(BusinessException.class);
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Dispensing dispensingService_viaId(long id) {
        return jdbc.queryForObject("SELECT * FROM dispensing WHERE id = ?",
                (rs, i) -> new Dispensing(rs.getLong("id"), rs.getString("request_id"), rs.getString("fingerprint"),
                        rs.getLong("mother_liquor_id"), rs.getLong("prescription_id"), rs.getLong("calibrator_id"),
                        rs.getInt("version"), rs.getLong("administration_time_epoch"),
                        (Double) rs.getObject("dispensed_activity_bq"), (Double) rs.getObject("deviation_ratio"),
                        com.example.starter.radio.domain.DispensingStatus.valueOf(rs.getString("status")),
                        rs.getString("first_checker"), rs.getString("second_checker"),
                        rs.getString("dispense_request_id"), rs.getString("dispense_fingerprint"),
                        (Long) rs.getObject("dispensed_at_epoch")),
                id);
    }

    /** 构造已完成双人核对、并已提交候选与第一签的夹具（便于需要 APPROVED 的用例）。 */
    private Fixture approvedFixture() {
        Fixture f = newCheckedFixture();
        DoseChangeOrder order = doseChangeService.submit(unique("REQ-CHG"), f.dispensingId, 1, NEW_EPOCH).order();
        doseChangeService.review(unique("REQ-REV1"), order.id(), "carol");
        doseChangeService.review(unique("REQ-REV2"), order.id(), "dave");
        return f;
    }

    /** 构造原版本已完成双人核对（CHECKED）的批次。 */
    private Fixture newCheckedFixture() {
        return newCheckedFixture(1000.0);
    }

    private Fixture newCheckedFixture(double prescribedActivityBq) {
        long calibratorId = registrationService.registerCalibrator(unique("CAL"), "合成活度计");
        calibratorService.dailyCheck(unique("REQ"), calibratorId, CHECK_DATE, 1000.0, 1000.0);
        var mother = registrationService.registerMotherLiquor(unique("ML"), "SYN-1H", 2200.0, T0.getEpochSecond());
        var prescription = registrationService.registerPrescription(unique("RX"), "SYN-1H",
                prescribedActivityBq, ADMIN_EPOCH);
        Dispensing d = dispensingService.calculate(unique("REQ-CALC"), mother.id(), prescription.id(), calibratorId);
        dispensingService.check(d.id(), "alice");
        dispensingService.check(d.id(), "bob");
        return new Fixture(mother.id(), prescription.id(), calibratorId, d.id());
    }

    private record Fixture(long motherId, long prescriptionId, long calibratorId, long dispensingId) {
    }
}
