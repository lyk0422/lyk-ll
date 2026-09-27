package com.example.starter.radio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.example.starter.radio.domain.ChangeStatus;
import com.example.starter.radio.domain.DispensingStatus;
import com.example.starter.radio.model.Dispensing;
import com.example.starter.radio.model.DispensingChange;
import com.example.starter.radio.model.DispensingVersion;
import com.example.starter.radio.model.MotherLiquor;
import com.example.starter.radio.model.Prescription;
import com.example.starter.radio.repository.MotherLiquorRepository;
import com.example.starter.radio.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 给药时刻变更流程的 H2 事务/并发测试。
 * 合成数据：SYN-1H 半衰期 3600s；母液 2200 Bq @ T0；处方 1000 Bq，原给药时刻 T0+1h（活度 1100，恰 +10%）。
 * 候选新时刻 T0+4500s：2200*2^(-1.25)≈924.985 Bq，偏差约 -7.50%，在 ±10% 内合法。
 */
class ChangeServiceTest extends IntegrationTestBase {

    private static final long ADMIN_EPOCH = T0.getEpochSecond() + 3600L;
    /** 合法候选新时刻：T0+4500s，候选活度约 924.985 Bq。 */
    private static final long NEW_ADMIN_EPOCH = T0.getEpochSecond() + 4500L;
    /** 超差候选时刻：T0+10h，活度约 0.2148 Bq，偏差远超 -10%。 */
    private static final long FAR_FUTURE_EPOCH = T0.getEpochSecond() + 36000L;

    @Autowired
    private ChangeService changeService;

    @Autowired
    private DispensingService dispensingService;

    @Autowired
    private CalibratorService calibratorService;

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private MotherLiquorRepository motherLiquors;

    @Test
    void submitOnlyCreatesCandidateAndKeepsEffectiveDispensingUnchanged() {
        Fixture f = newCalculatedFixture();
        Dispensing before = dispensingById(f.dispensingId);

        DispensingChange change = changeService.submit(unique("REQ-CHG"), f.dispensingId,
                before.version(), NEW_ADMIN_EPOCH);

        assertThat(change.status()).isEqualTo(ChangeStatus.PENDING);
        assertThat(change.candidateVersionNo()).isEqualTo(2L);
        assertThat(change.candidateActivityBq()).isBetween(924.0, 926.0);
        assertThat(change.newAdministrationTimeEpoch()).isEqualTo(NEW_ADMIN_EPOCH);
        // 候选期间有效分装不变
        Dispensing after = dispensingById(f.dispensingId);
        assertThat(after.version()).isEqualTo(1L);
        assertThat(after.administrationTimeEpoch()).isEqualTo(ADMIN_EPOCH);
        assertThat(after.dispensedActivityBq()).isEqualTo(1100.0);
        assertThat(after.status()).isEqualTo(DispensingStatus.CALCULATED);
        // 候选期间不产生新版本证据，版本1仍只有一条
        assertThat(changeService.versions(f.dispensingId)).hasSize(1);
    }

    @Test
    void approvalReplacesVersionAtomicallyAndPreservesOldEvidence() {
        Fixture f = newCheckedFixture();
        String req = unique("REQ-CHG");
        DispensingChange change = changeService.submit(req, f.dispensingId, 1L, NEW_ADMIN_EPOCH);
        changeService.review(change.id(), "carol");
        ChangeService.ReviewResult result = changeService.review(change.id(), "dave");

        assertThat(result.rejected()).isFalse();
        Dispensing effective = dispensingById(f.dispensingId);
        assertThat(effective.version()).isEqualTo(2L);
        assertThat(effective.administrationTimeEpoch()).isEqualTo(NEW_ADMIN_EPOCH);
        assertThat(effective.dispensedActivityBq()).isEqualTo(result.change().candidateActivityBq());
        // 生效后状态回到待核对，旧双人核对有效性被清除
        assertThat(effective.status()).isEqualTo(DispensingStatus.CALCULATED);
        assertThat(effective.firstChecker()).isNull();
        assertThat(effective.secondChecker()).isNull();

        // 旧版本计算与签认保留为证据
        List<DispensingVersion> versions = changeService.versions(f.dispensingId);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).versionNo()).isEqualTo(1L);
        assertThat(versions.get(0).source()).isEqualTo("INITIAL");
        assertThat(versions.get(0).dispensedActivityBq()).isEqualTo(1100.0);
        assertThat(versions.get(1).versionNo()).isEqualTo(2L);
        assertThat(versions.get(1).source()).isEqualTo("CHANGE");
        assertThat(jdbc.queryForList(
                "SELECT version_no, checker, check_seq FROM dispensing_check WHERE dispensing_id = ? ORDER BY id",
                f.dispensingId)).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispensing_check WHERE dispensing_id = ? AND version_no = 1",
                Integer.class, f.dispensingId)).isEqualTo(2);
    }

    @Test
    void afterApprovalOldSignaturesCannotReuseAndNewVersionMustBeRechecked() {
        Fixture f = newCheckedFixture();
        DispensingChange change = submitAndApprove(f.dispensingId);
        assertThat(change.status()).isEqualTo(ChangeStatus.APPROVED);

        // 未对新版本重新核对即发药：拒绝
        assertThatThrownBy(() -> dispensingService.dispense(unique("REQ-DISP"), f.dispensingId,
                0.0, NEW_ADMIN_EPOCH)).isInstanceOf(BusinessException.class);

        // 新版本重新双人核对（允许与旧版本同一批人员）后可发药
        dispensingService.check(f.dispensingId, "alice");
        dispensingService.check(f.dispensingId, "bob");
        // 勾稽（新时刻）：2200 = 924.985(分装) + 0(剩余) + 1275.015(衰变残量)
        DispensingService.DispenseResult result = dispensingService.dispense(unique("REQ-DISP"),
                f.dispensingId, 0.0, NEW_ADMIN_EPOCH);
        assertThat(result.dispensing().status()).isEqualTo(DispensingStatus.DISPENSED);
        assertThat(result.ledger().balanced()).isTrue();
        assertThat(result.dispensing().version()).isEqualTo(2L);
    }

    @Test
    void dispenseFirstWinsRaceAndChangeIsRejectedWithEvidence() {
        Fixture f = newCheckedFixture();
        DispensingChange change = changeService.submit(unique("REQ-CHG"), f.dispensingId, 1L, NEW_ADMIN_EPOCH);
        changeService.review(change.id(), "carol");

        // 原版本先发药
        dispensingService.dispense(unique("REQ-DISP"), f.dispensingId, 0.0, ADMIN_EPOCH);

        // 第二名复核：竞争失败，候选落 REJECTED 终态，不改动已发药历史
        ChangeService.ReviewResult result = changeService.review(change.id(), "dave");
        assertThat(result.rejected()).isTrue();
        assertThat(result.change().status()).isEqualTo(ChangeStatus.REJECTED);
        Dispensing dispensing = dispensingById(f.dispensingId);
        assertThat(dispensing.status()).isEqualTo(DispensingStatus.DISPENSED);
        assertThat(dispensing.version()).isEqualTo(1L);
        assertThat(dispensing.administrationTimeEpoch()).isEqualTo(ADMIN_EPOCH);
        // 已拒绝候选再复核报 422
        assertThatThrownBy(() -> changeService.review(change.id(), "erin"))
                .isInstanceOf(BusinessException.class);
        // 已发药历史不得重开：新变更被 422
        assertThatThrownBy(() -> changeService.submit(unique("REQ-CHG2"), f.dispensingId, 1L, NEW_ADMIN_EPOCH))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void changeFirstWinsRaceAndOldVersionDispenseRejected() {
        Fixture f = newCheckedFixture();
        submitAndApprove(f.dispensingId);

        // 变更先成：有效分装回到 CALCULATED，旧版本发药拒绝
        assertThatThrownBy(() -> dispensingService.dispense(unique("REQ-DISP"), f.dispensingId,
                0.0, NEW_ADMIN_EPOCH)).isInstanceOf(BusinessException.class);
        // 不存在“新时刻+旧活度”混合状态：当前活度已是新时刻重算值
        Dispensing d = dispensingById(f.dispensingId);
        DispensingVersion v2 = changeService.versions(f.dispensingId).get(1);
        assertThat(d.dispensedActivityBq()).isEqualTo(v2.dispensedActivityBq());
        assertThat(d.administrationTimeEpoch()).isEqualTo(v2.administrationTimeEpoch());
    }

    @Test
    void outOfToleranceCandidateRejectsWithoutDeductionAndDoesNotOccupyKey() {
        Fixture f = newCalculatedFixture();
        String requestId = unique("REQ-CHG");
        // 超差候选：422，不扣减母液
        assertThatThrownBy(() -> changeService.submit(requestId, f.dispensingId, 1L, FAR_FUTURE_EPOCH))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("偏差");
        MotherLiquor mother = motherLiquors.findById(f.motherId).orElseThrow();
        assertThat(mother.remainingActivityBq()).isEqualTo(2200.0);
        assertThat(changeService.changes(f.dispensingId)).isEmpty();

        // 失败不占键：同 requestId 改合法参数可成功提交
        DispensingChange retry = changeService.submit(requestId, f.dispensingId, 1L, NEW_ADMIN_EPOCH);
        assertThat(retry.status()).isEqualTo(ChangeStatus.PENDING);
    }

    @Test
    void closedMotherLiquorFailsInventoryValidationWithoutOccupyingKey() {
        Fixture f = newCalculatedFixture();
        jdbc.update("UPDATE mother_liquor SET status = 'CLOSED' WHERE id = ?", f.motherId);
        String requestId = unique("REQ-CHG");
        assertThatThrownBy(() -> changeService.submit(requestId, f.dispensingId, 1L, NEW_ADMIN_EPOCH))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("库存");
        // 不占键
        jdbc.update("UPDATE mother_liquor SET status = 'OPEN' WHERE id = ?", f.motherId);
        assertThat(changeService.submit(requestId, f.dispensingId, 1L, NEW_ADMIN_EPOCH).status())
                .isEqualTo(ChangeStatus.PENDING);
    }

    @Test
    void submitValidationFailures() {
        Fixture f = newCalculatedFixture();
        // 不存在 → 404
        assertThatThrownBy(() -> changeService.submit(unique("REQ-CHG"), 999999L, 1L, NEW_ADMIN_EPOCH))
                .isInstanceOf(NotFoundException.class);
        // expectedVersion 不匹配 → 409
        assertThatThrownBy(() -> changeService.submit(unique("REQ-CHG"), f.dispensingId, 7L, NEW_ADMIN_EPOCH))
                .isInstanceOf(ConflictException.class);
        // 新时刻不晚于母液参考时刻 → 422
        assertThatThrownBy(() -> changeService.submit(unique("REQ-CHG"), f.dispensingId, 1L,
                T0.getEpochSecond())).isInstanceOf(BusinessException.class);
        // 已存在待复核候选时再次提交 → 422
        changeService.submit(unique("REQ-CHG"), f.dispensingId, 1L, NEW_ADMIN_EPOCH);
        assertThatThrownBy(() -> changeService.submit(unique("REQ-CHG-OTHER"), f.dispensingId, 1L,
                NEW_ADMIN_EPOCH)).isInstanceOf(BusinessException.class);
    }

    @Test
    void submitIsIdempotentAndReplayIgnoresLaterInventoryChanges() {
        Fixture f = newCalculatedFixture();
        String requestId = unique("REQ-CHG");
        DispensingChange first = changeService.submit(requestId, f.dispensingId, 1L, NEW_ADMIN_EPOCH);
        DispensingChange replay = changeService.submit(requestId, f.dispensingId, 1L, NEW_ADMIN_EPOCH);
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.candidateActivityBq()).isEqualTo(first.candidateActivityBq());

        // 同键异参（新时刻不同）→ 409
        assertThatThrownBy(() -> changeService.submit(requestId, f.dispensingId, 1L,
                T0.getEpochSecond() + 4200L)).isInstanceOf(ConflictException.class);

        // 随后母液剩余量变化不影响冻结结果（重算基于版本1快照）
        motherLiquors.updateRemaining(f.motherId, 123.0, ADMIN_EPOCH);
        DispensingChange replayAgain = changeService.submit(requestId, f.dispensingId, 1L, NEW_ADMIN_EPOCH);
        assertThat(replayAgain.id()).isEqualTo(first.id());
        assertThat(replayAgain.candidateActivityBq()).isEqualTo(first.candidateActivityBq());
    }

    @Test
    void reviewRequiresTwoDistinctPeopleAndCancelLifecycle() {
        Fixture f = newCalculatedFixture();
        DispensingChange change = changeService.submit(unique("REQ-CHG"), f.dispensingId, 1L, NEW_ADMIN_EPOCH);
        changeService.review(change.id(), "carol");
        // 单人重复复核 → 409
        assertThatThrownBy(() -> changeService.review(change.id(), "carol"))
                .isInstanceOf(ConflictException.class);

        // 作废
        DispensingChange voided = changeService.cancel(change.id());
        assertThat(voided.status()).isEqualTo(ChangeStatus.VOID);
        // 重复作废幂等
        assertThat(changeService.cancel(change.id()).status()).isEqualTo(ChangeStatus.VOID);
        // 已作废禁止复核
        assertThatThrownBy(() -> changeService.review(change.id(), "dave"))
                .isInstanceOf(BusinessException.class);
        // 作废后可重新提交候选
        DispensingChange second = changeService.submit(unique("REQ-CHG2"), f.dispensingId, 1L, NEW_ADMIN_EPOCH);
        assertThat(second.status()).isEqualTo(ChangeStatus.PENDING);
    }

    @Test
    void versionIncrementsFromOneAndChainedChangesBuildVersions() {
        Fixture f = newCheckedFixture();
        assertThat(dispensingById(f.dispensingId).version()).isEqualTo(1L);
        submitAndApprove(f.dispensingId);
        assertThat(dispensingById(f.dispensingId).version()).isEqualTo(2L);

        // 第二轮变更：expectedVersion=2，候选版本号=3
        Dispensing d = dispensingById(f.dispensingId);
        DispensingChange change2 = changeService.submit(unique("REQ-CHG2"), f.dispensingId,
                d.version(), T0.getEpochSecond() + 3600L);
        assertThat(change2.expectedVersion()).isEqualTo(2L);
        assertThat(change2.candidateVersionNo()).isEqualTo(3L);
        // 旧 expectedVersion 再提交 → 409
        assertThatThrownBy(() -> changeService.submit(unique("REQ-CHG3"), f.dispensingId, 1L,
                T0.getEpochSecond() + 3600L)).isInstanceOf(ConflictException.class);
        changeService.review(change2.id(), "carol");
        changeService.review(change2.id(), "dave");
        assertThat(dispensingById(f.dispensingId).version()).isEqualTo(3L);
        assertThat(changeService.versions(f.dispensingId)).hasSize(3);
    }

    @Test
    void concurrentDispenseAndApprovalOnlyOneSideCommits() throws Exception {
        // 真实并发由分装行锁 FOR UPDATE 串行化：谁先拿到锁谁先提交，另一方必失败。
        // 两种先后次序的分支另由 dispenseFirstWinsRace* / changeFirstWinsRace* 两个确定性用例覆盖，
        // 这里只断言并发不变量（恰一方成功）与最终数据，不对调度顺序作易脆假设。
        int iterations = 8;
        int dispenseWins = 0;
        int changeWins = 0;
        for (int i = 0; i < iterations; i++) {
            Fixture f = newCheckedFixture();
            DispensingChange change = changeService.submit(unique("REQ-CHG"), f.dispensingId, 1L, NEW_ADMIN_EPOCH);
            changeService.review(change.id(), "carol");
            long changeId = change.id();
            long dispensingId = f.dispensingId;

            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> dispenseError = new AtomicReference<>();
            AtomicReference<Throwable> reviewError = new AtomicReference<>();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> dispenseFuture = pool.submit(() -> {
                    await(start);
                    try {
                        dispensingService.dispense(unique("REQ-DISP"), dispensingId, 0.0, ADMIN_EPOCH);
                    } catch (Throwable t) {
                        dispenseError.set(t);
                    }
                });
                Future<?> reviewFuture = pool.submit(() -> {
                    await(start);
                    try {
                        changeService.review(changeId, "dave");
                    } catch (Throwable t) {
                        reviewError.set(t);
                    }
                });
                start.countDown();
                dispenseFuture.get(30, TimeUnit.SECONDS);
                reviewFuture.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            Dispensing terminal = dispensingById(dispensingId);
            DispensingChange terminalChange = changeService.change(changeId);
            boolean dispensed = dispenseError.get() == null;
            boolean approved = terminalChange.status() == ChangeStatus.APPROVED;
            // 只可一方先提交：发药成功则变更必被拒；变更生效则发药必失败
            assertThat(dispensed ^ approved)
                    .as("iteration %d: dispensed=%s approved=%s reviewErr=%s",
                            i, dispensed, approved, reviewError.get()).isTrue();
            if (dispensed) {
                dispenseWins++;
                assertThat(terminal.status()).isEqualTo(DispensingStatus.DISPENSED);
                assertThat(terminalChange.status()).isEqualTo(ChangeStatus.REJECTED);
                assertThat(reviewError.get()).isNull();
            } else {
                changeWins++;
                assertThat(terminal.status()).isEqualTo(DispensingStatus.CALCULATED);
                assertThat(dispenseError.get()).isInstanceOf(BusinessException.class);
            }
        }
        // 每轮恰一方先提交，且有效分装无“新时刻+旧活度”混合状态
        assertThat(dispenseWins + changeWins).isEqualTo(iterations);
    }

    private DispensingChange submitAndApprove(long dispensingId) {
        DispensingChange change = changeService.submit(unique("REQ-CHG"), dispensingId, 1L, NEW_ADMIN_EPOCH);
        changeService.review(change.id(), "carol");
        ChangeService.ReviewResult result = changeService.review(change.id(), "dave");
        assertThat(result.rejected()).isFalse();
        return result.change();
    }

    private Dispensing dispensingById(long id) {
        return jdbc.queryForObject("SELECT * FROM dispensing WHERE id = ?", (rs, n) -> new Dispensing(
                rs.getLong("id"), rs.getString("request_id"), rs.getString("fingerprint"),
                rs.getLong("mother_liquor_id"), rs.getLong("prescription_id"), rs.getLong("calibrator_id"),
                (Double) rs.getObject("dispensed_activity_bq"),
                (Double) rs.getObject("deviation_ratio"),
                rs.getLong("version"),
                (Long) rs.getObject("administration_time_epoch"),
                DispensingStatus.valueOf(rs.getString("status")),
                rs.getString("first_checker"), rs.getString("second_checker"),
                rs.getString("dispense_request_id"), rs.getString("dispense_fingerprint"),
                (Long) rs.getObject("dispensed_at_epoch")), id);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Fixture newCalculatedFixture() {
        long calibratorId = registrationService.registerCalibrator(unique("CAL"), "合成活度计");
        calibratorService.dailyCheck(unique("REQ"), calibratorId, CHECK_DATE, 1000.0, 1000.0);
        MotherLiquor mother = registrationService.registerMotherLiquor(unique("ML"), "SYN-1H",
                2200.0, T0.getEpochSecond());
        Prescription prescription = registrationService.registerPrescription(unique("RX"), "SYN-1H",
                1000.0, ADMIN_EPOCH);
        Dispensing d = dispensingService.calculate(unique("REQ-CALC"), mother.id(), prescription.id(), calibratorId);
        return new Fixture(mother.id(), prescription.id(), calibratorId, d.id());
    }

    private Fixture newCheckedFixture() {
        Fixture f = newCalculatedFixture();
        dispensingService.check(f.dispensingId, "alice");
        dispensingService.check(f.dispensingId, "bob");
        return f;
    }

    private record Fixture(long motherId, long prescriptionId, long calibratorId, long dispensingId) {
    }
}
