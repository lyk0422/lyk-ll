package com.example.starter.radio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import com.example.starter.radio.domain.DispensingStatus;
import com.example.starter.radio.model.Dispensing;
import com.example.starter.radio.model.MotherLiquor;
import com.example.starter.radio.model.Prescription;
import com.example.starter.radio.model.ReconciliationLedger;
import com.example.starter.radio.repository.MotherLiquorRepository;
import com.example.starter.radio.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 分装计算、双人核对与发药勾稽的 H2 集成测试。
 * 合成数据：SYN-1H 半衰期 3600s；母液 2200 Bq @ T0；给药时刻 T0+1h，衰变后恰 1100 Bq。
 */
class DispensingServiceTest extends IntegrationTestBase {

    private static final long ADMIN_EPOCH = T0.getEpochSecond() + 3600L;

    @Autowired
    private DispensingService dispensingService;

    @Autowired
    private CalibratorService calibratorService;

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private MotherLiquorRepository motherLiquors;

    @Test
    void decaysToAdministrationTimeAndExactlyTenPercentIsLegal() {
        Fixture f = newFixture(1000.0);
        Dispensing d = dispensingService.calculate(unique("REQ"), f.motherId, f.prescriptionId, f.calibratorId);
        // 2200 Bq 经 1 个半衰期推算至给药时刻恰为 1100 Bq；与处方 1000 Bq 偏差恰 10%，合法
        assertThat(d.dispensedActivityBq()).isEqualTo(1100.0);
        assertThat(d.deviationRatio()).isEqualTo(0.1);
        assertThat(d.status()).isEqualTo(DispensingStatus.CALCULATED);
    }

    @Test
    void overTenPercentDeviationIsRejected() {
        Fixture f = newFixture(900.0);
        Dispensing d = dispensingService.calculate(unique("REQ"), f.motherId, f.prescriptionId, f.calibratorId);
        assertThat(d.status()).isEqualTo(DispensingStatus.REJECTED);
        assertThat(d.dispensedActivityBq()).isNull();
        assertThat(d.deviationRatio()).isGreaterThan(0.10);
    }

    @Test
    void calculateIsIdempotentAndRejectsSameKeyDifferentParams() {
        Fixture f = newFixture(1000.0);
        String requestId = unique("REQ");
        Dispensing first = dispensingService.calculate(requestId, f.motherId, f.prescriptionId, f.calibratorId);
        Dispensing replay = dispensingService.calculate(requestId, f.motherId, f.prescriptionId, f.calibratorId);
        assertThat(replay.id()).isEqualTo(first.id());

        Fixture other = newFixture(1000.0);
        assertThatThrownBy(() -> dispensingService.calculate(requestId, other.motherId,
                other.prescriptionId, other.calibratorId))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void calibratorDisabledForTheDayAfterFailedCheck() {
        long calibratorId = registrationService.registerCalibrator(unique("CAL"), "合成活度计");
        calibratorService.dailyCheck(unique("REQ"), calibratorId, CHECK_DATE, 1000.0, 1051.0);
        MotherLiquor mother = registrationService.registerMotherLiquor(unique("ML"), "SYN-1H",
                2200.0, T0.getEpochSecond());
        Prescription prescription = registrationService.registerPrescription(unique("RX"), "SYN-1H",
                1000.0, ADMIN_EPOCH);
        assertThatThrownBy(() -> dispensingService.calculate(unique("REQ"), mother.id(),
                prescription.id(), calibratorId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("日检");
    }

    @Test
    void dualCheckRequiresTwoDistinctCheckers() {
        Fixture f = newFixture(1000.0);
        Dispensing calculated = dispensingService.calculate(unique("REQ"), f.motherId,
                f.prescriptionId, f.calibratorId);
        Dispensing afterFirst = dispensingService.check(calculated.id(), "alice");
        assertThat(afterFirst.firstChecker()).isEqualTo("alice");
        assertThat(afterFirst.status()).isEqualTo(DispensingStatus.CALCULATED);

        // 单人重复核对不生效
        assertThatThrownBy(() -> dispensingService.check(calculated.id(), "alice"))
                .isInstanceOf(ConflictException.class);

        Dispensing afterSecond = dispensingService.check(calculated.id(), "bob");
        assertThat(afterSecond.secondChecker()).isEqualTo("bob");
        assertThat(afterSecond.status()).isEqualTo(DispensingStatus.CHECKED);
    }

    @Test
    void dispenseBeforeDualCheckRejected() {
        Fixture f = newFixture(1000.0);
        Dispensing d = dispensingService.calculate(unique("REQ"), f.motherId, f.prescriptionId, f.calibratorId);
        assertThatThrownBy(() -> dispensingService.dispense(unique("REQ"), d.id(), 0.0, ADMIN_EPOCH))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void dispenseReconcilesAndDeductsMotherLiquorAtomically() {
        Fixture f = newCheckedFixture();
        DispensingService.DispenseResult result = dispensingService.dispense(unique("REQ"),
                f.dispensingId, 0.0, ADMIN_EPOCH);
        assertThat(result.replayed()).isFalse();
        assertThat(result.dispensing().status()).isEqualTo(DispensingStatus.DISPENSED);
        // 勾稽：2200 = 1100(分装总量) + 0(剩余) + 1100(衰变残量)
        ReconciliationLedger ledger = result.ledger();
        assertThat(ledger.balanced()).isTrue();
        assertThat(ledger.initialActivityBq()).isEqualTo(2200.0);
        assertThat(ledger.dispensedTotalBq()).isEqualTo(1100.0);
        assertThat(ledger.remainingActivityBq()).isEqualTo(0.0);
        assertThat(ledger.decayResidualBq()).isEqualTo(1100.0);
        // 发药与扣减同事务：库存必须变化
        MotherLiquor mother = motherLiquors.findById(f.motherId).orElseThrow();
        assertThat(mother.remainingActivityBq()).isEqualTo(0.0);
        assertThat(mother.remainingTimeEpoch()).isEqualTo(ADMIN_EPOCH);
    }

    @Test
    void unbalancedReconciliationRejectsAndKeepsInventoryUnchanged() {
        Fixture f = newCheckedFixture();
        assertThatThrownBy(() -> dispensingService.dispense(unique("REQ"), f.dispensingId, 500.0, ADMIN_EPOCH))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("勾稽");
        // 事务回滚：母液剩余活度不变，批次仍为已核对
        MotherLiquor mother = motherLiquors.findById(f.motherId).orElseThrow();
        assertThat(mother.remainingActivityBq()).isEqualTo(2200.0);
        assertThat(jdbc.queryForObject("SELECT status FROM dispensing WHERE id = ?",
                String.class, f.dispensingId)).isEqualTo("CHECKED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_ledger",
                Integer.class)).isZero();
    }

    @Test
    void dispenseReplayReturnsFirstResultAndTerminalRepeatSucceeds() {
        Fixture f = newCheckedFixture();
        String requestId = unique("REQ");
        DispensingService.DispenseResult first = dispensingService.dispense(requestId,
                f.dispensingId, 0.0, ADMIN_EPOCH);
        // 同键同参：返回首次结果，不重复扣减
        DispensingService.DispenseResult replay = dispensingService.dispense(requestId,
                f.dispensingId, 0.0, ADMIN_EPOCH);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.dispensing().dispensedAtEpoch()).isEqualTo(first.dispensing().dispensedAtEpoch());
        // 同键异参：409，且不重复扣减
        assertThatThrownBy(() -> dispensingService.dispense(requestId, f.dispensingId, 1.0, ADMIN_EPOCH))
                .isInstanceOf(ConflictException.class);
        // 终态重复完成：幂等成功
        DispensingService.DispenseResult repeat = dispensingService.dispense(unique("REQ"),
                f.dispensingId, 0.0, ADMIN_EPOCH);
        assertThat(repeat.replayed()).isTrue();
        assertThat(repeat.dispensing().status()).isEqualTo(DispensingStatus.DISPENSED);
        // 全程只扣减一次、台账只有一条
        MotherLiquor mother = motherLiquors.findById(f.motherId).orElseThrow();
        assertThat(mother.remainingActivityBq()).isEqualTo(0.0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_ledger WHERE dispensing_id = ?",
                Integer.class, f.dispensingId)).isEqualTo(1);
    }

    @Test
    void lateMeasurementAfterCompletionOnlyRecordsLateEvent() {
        Fixture f = newCheckedFixture();
        dispensingService.dispense(unique("REQ"), f.dispensingId, 0.0, ADMIN_EPOCH);
        String result = dispensingService.recordMeasurement(f.dispensingId, "late-reading=0.0Bq");
        assertThat(result).isEqualTo("LATE_RECORDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM late_event WHERE dispensing_id = ?",
                Integer.class, f.dispensingId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM dispensing WHERE id = ?",
                String.class, f.dispensingId)).isEqualTo("DISPENSED");
    }

    @Test
    void reconciliationLedgerIsRecomputable() {
        Fixture f = newCheckedFixture();
        dispensingService.dispense(unique("REQ"), f.dispensingId, 0.0, ADMIN_EPOCH);
        ReconciliationLedger recomputed = dispensingService.recompute(f.dispensingId);
        assertThat(recomputed.balanced()).isTrue();
        assertThat(recomputed.initialActivityBq()).isEqualTo(2200.0);
        assertThat(recomputed.dispensedTotalBq()).isEqualTo(1100.0);
        assertThat(recomputed.remainingActivityBq()).isEqualTo(0.0);
        assertThat(recomputed.decayResidualBq()).isEqualTo(1100.0);
    }

    /** 构造已完成双人核对的批次夹具。 */
    private Fixture newCheckedFixture() {
        Fixture f = newFixture(1000.0);
        Dispensing d = dispensingService.calculate(unique("REQ"), f.motherId, f.prescriptionId, f.calibratorId);
        dispensingService.check(d.id(), "alice");
        dispensingService.check(d.id(), "bob");
        return new Fixture(f.motherId, f.prescriptionId, f.calibratorId, d.id());
    }

    /** 构造母液、处方、当日日检通过的活度计夹具。 */
    private Fixture newFixture(double prescribedActivityBq) {
        long calibratorId = registrationService.registerCalibrator(unique("CAL"), "合成活度计");
        calibratorService.dailyCheck(unique("REQ"), calibratorId, CHECK_DATE, 1000.0, 1000.0);
        MotherLiquor mother = registrationService.registerMotherLiquor(unique("ML"), "SYN-1H",
                2200.0, T0.getEpochSecond());
        Prescription prescription = registrationService.registerPrescription(unique("RX"), "SYN-1H",
                prescribedActivityBq, ADMIN_EPOCH);
        return new Fixture(mother.id(), prescription.id(), calibratorId, 0L);
    }

    private record Fixture(long motherId, long prescriptionId, long calibratorId, long dispensingId) {
    }
}
