package com.example.starter.radio.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import com.example.starter.radio.domain.DispensingStatus;
import com.example.starter.radio.model.Dispensing;
import com.example.starter.radio.model.MotherLiquor;
import com.example.starter.radio.model.Nuclide;
import com.example.starter.radio.model.Prescription;
import com.example.starter.radio.model.ReconciliationLedger;
import com.example.starter.radio.repository.CalibratorRepository;
import com.example.starter.radio.repository.ChangeOrderRepository;
import com.example.starter.radio.repository.DispensingRepository;
import com.example.starter.radio.repository.MotherLiquorRepository;
import com.example.starter.radio.repository.NuclideRepository;
import com.example.starter.radio.repository.PrescriptionRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分装计算、双人核对与发药勾稽。
 * 分装活度按衰变公式推算至给药时刻（UTC），与处方偏差恰 10% 合法、超过 10% 拒绝；
 * 发药与母液活度扣减在同一事务内完成；发药完成为终态，重复完成返回幂等成功。
 * 每次计算（含变更生效后的重算）在 dispensing_version 留存不可变版本证据，核对链按版本隔离。
 */
@Service
public class DispensingService {

    /** 分装与处方活度偏差阈值：绝对值不超过 10%。 */
    public static final double DISPENSE_TOLERANCE = 0.10;

    /** 勾稽平衡判定的相对容差（合成数据按 2 的幂衰变可精确复算，容差仅吸收浮点尾差）。 */
    private static final double BALANCE_RELATIVE_TOLERANCE = 1e-9;

    private final DispensingRepository dispensings;
    private final MotherLiquorRepository motherLiquors;
    private final PrescriptionRepository prescriptions;
    private final CalibratorRepository calibrators;
    private final NuclideRepository nuclides;
    private final ChangeOrderRepository changeOrders;
    private final DecayService decay;
    private final Clock clock;

    public DispensingService(DispensingRepository dispensings,
                             MotherLiquorRepository motherLiquors,
                             PrescriptionRepository prescriptions,
                             CalibratorRepository calibrators,
                             NuclideRepository nuclides,
                             ChangeOrderRepository changeOrders,
                             DecayService decay,
                             Clock clock) {
        this.dispensings = dispensings;
        this.motherLiquors = motherLiquors;
        this.prescriptions = prescriptions;
        this.calibrators = calibrators;
        this.nuclides = nuclides;
        this.changeOrders = changeOrders;
        this.decay = decay;
        this.clock = clock;
    }

    /**
     * 分装计算：幂等；活度计须当日日检通过；母液与处方核素须一致。
     */
    @Transactional
    public Dispensing calculate(String requestId, long motherLiquorId, long prescriptionId, long calibratorId) {
        String fingerprint = Fingerprints.of(requestId, motherLiquorId, prescriptionId, calibratorId);
        var existing = dispensings.findDispensingByRequestId(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }

        MotherLiquor mother = motherLiquors.findById(motherLiquorId)
                .orElseThrow(() -> new NotFoundException("母液批次不存在: " + motherLiquorId));
        Prescription prescription = prescriptions.findById(prescriptionId)
                .orElseThrow(() -> new NotFoundException("处方不存在: " + prescriptionId));
        calibrators.findCalibratorById(calibratorId)
                .orElseThrow(() -> new NotFoundException("活度计不存在: " + calibratorId));
        if (mother.nuclideId() != prescription.nuclideId()) {
            throw new BusinessException("母液与处方核素不一致，拒绝分装");
        }
        Nuclide nuclide = nuclides.findById(mother.nuclideId())
                .orElseThrow(() -> new IllegalStateException("核素表缺少记录: " + mother.nuclideId()));

        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        var dailyCheck = calibrators.findDailyCheck(calibratorId, today);
        if (dailyCheck.isEmpty() || !dailyCheck.get().passed()) {
            throw new BusinessException("活度计当日日检未通过，禁止使用: calibratorId=" + calibratorId);
        }

        double dispensedAtAdmin = decay.activityAt(mother.initialActivityBq(), mother.referenceTimeEpoch(),
                prescription.administrationTimeEpoch(), nuclide.halfLifeSeconds());
        double deviation = (dispensedAtAdmin - prescription.prescribedActivityBq())
                / prescription.prescribedActivityBq();
        boolean legal = Math.abs(deviation) <= DISPENSE_TOLERANCE;

        long now = clock.instant().getEpochSecond();
        try {
            if (legal) {
                long id = dispensings.insertDispensing(requestId, fingerprint, motherLiquorId, prescriptionId,
                        calibratorId, dispensedAtAdmin, deviation, DispensingStatus.CALCULATED, now);
                dispensings.insertVersion(id, 1, prescriptionId, prescription.administrationTimeEpoch(),
                        dispensedAtAdmin, deviation, "LEGAL", null, now);
                return dispensings.findDispensingById(id).orElseThrow();
            }
            long id = dispensings.insertDispensing(requestId, fingerprint, motherLiquorId, prescriptionId,
                    calibratorId, null, deviation, DispensingStatus.REJECTED, now);
            dispensings.insertVersion(id, 1, prescriptionId, prescription.administrationTimeEpoch(),
                    null, deviation, "REJECTED", null, now);
            return dispensings.findDispensingById(id).orElseThrow();
        } catch (DuplicateKeyException e) {
            return dispensings.findDispensingByRequestId(requestId)
                    .map(d -> replayOrConflict(d, fingerprint))
                    .orElseThrow(() -> new IllegalStateException("分装幂等记录写入冲突后读取失败", e));
        }
    }

    /**
     * 双人核对：两名不同人员先后核对，单人重复核对不生效（409）。
     */
    @Transactional
    public Dispensing check(long dispensingId, String checker) {
        Dispensing dispensing = dispensings.findDispensingByIdForUpdate(dispensingId)
                .orElseThrow(() -> new NotFoundException("分装批次不存在: " + dispensingId));
        if (dispensing.status() == DispensingStatus.REJECTED
                || dispensing.status() == DispensingStatus.DISPENSED
                || dispensing.status() == DispensingStatus.VOID) {
            throw new BusinessException("分装批次已处于终态，禁止核对: " + dispensing.status());
        }
        long now = clock.instant().getEpochSecond();
        if (dispensing.firstChecker() == null) {
            dispensings.updateChecker(dispensingId, "first_checker", checker, now);
            dispensings.insertCheck(dispensingId, dispensing.versionNo(), checker, 1, now);
            return dispensings.findDispensingById(dispensingId).orElseThrow();
        }
        if (dispensing.firstChecker().equals(checker)) {
            throw new ConflictException("双人核对须为两名不同人员，单人重复核对不生效: " + checker);
        }
        if (dispensing.secondChecker() != null) {
            throw new BusinessException("双人核对已完成，禁止重复核对");
        }
        dispensings.updateChecker(dispensingId, "second_checker", checker, now);
        dispensings.insertCheck(dispensingId, dispensing.versionNo(), checker, 2, now);
        dispensings.updateStatus(dispensingId, DispensingStatus.CHECKED, now);
        return dispensings.findDispensingById(dispensingId).orElseThrow();
    }

    /**
     * 发药：勾稽“母液初始活度＝分装总量＋剩余活度＋衰变残量”，不等拒绝发药；
     * 发药与母液剩余活度扣减在同一事务内完成；发药完成为终态，重复完成返回幂等成功。
     */
    @Transactional
    public DispenseResult dispense(String requestId, long dispensingId,
                                   double remainingActivityBq, long measuredAtEpoch) {
        String fingerprint = Fingerprints.of(requestId, dispensingId, remainingActivityBq, measuredAtEpoch);
        Dispensing dispensing = dispensings.findDispensingByIdForUpdate(dispensingId)
                .orElseThrow(() -> new NotFoundException("分装批次不存在: " + dispensingId));

        if (dispensing.status() == DispensingStatus.DISPENSED) {
            if (requestId.equals(dispensing.dispenseRequestId())) {
                if (!fingerprint.equals(dispensing.dispenseFingerprint())) {
                    throw new ConflictException("相同 requestId 但业务字段不一致，拒绝重复发药: " + requestId);
                }
                return new DispenseResult(dispensing, dispensings.findLedger(dispensingId).orElse(null), true);
            }
            // 终态重复完成：幂等成功，不重复扣减库存
            return new DispenseResult(dispensing, dispensings.findLedger(dispensingId).orElse(null), true);
        }
        if (dispensing.status() == DispensingStatus.VOID) {
            throw new BusinessException("分装批次已撤回作废（VOID 终态），禁止发药且不得重新激活");
        }
        if (dispensing.status() != DispensingStatus.CHECKED) {
            throw new BusinessException("分装批次未完成双人核对或已拒绝，禁止发药: " + dispensing.status());
        }

        long now = clock.instant().getEpochSecond();
        // 原版本抢先发药：挂起候选即时失效（SUPERSEDED）。已先持有分装行锁再锁候选行，与变更生效同一锁顺序，
        // 因而“候选批准 vs 原版本发药”只有一方先提交。
        changeOrders.findActiveByDispensingForUpdate(dispensingId)
                .ifPresent(pending -> changeOrders.markSuperseded(pending.id(), now));

        MotherLiquor mother = motherLiquors.findById(dispensing.motherLiquorId()).orElseThrow();
        Nuclide nuclide = nuclides.findById(mother.nuclideId()).orElseThrow();
        ReconciliationLedger ledger = reconcile(dispensing, mother, nuclide,
                remainingActivityBq, measuredAtEpoch);
        if (!ledger.balanced()) {
            throw new BusinessException(String.format(
                    "活度勾稽不平衡，拒绝发药: 初始=%.6f 分装总量=%.6f 剩余=%.6f 衰变残量=%.6f",
                    ledger.initialActivityBq(), ledger.dispensedTotalBq(),
                    ledger.remainingActivityBq(), ledger.decayResidualBq()));
        }

        dispensings.insertLedger(ledger, now);
        dispensings.markDispensed(dispensingId, requestId, fingerprint, now);
        // 发药与母液活度扣减同事务：任一步失败整体回滚
        motherLiquors.updateRemaining(mother.id(), remainingActivityBq, measuredAtEpoch);
        Dispensing done = dispensings.findDispensingById(dispensingId).orElseThrow();
        return new DispenseResult(done, ledger, false);
    }

    /**
     * 批次完成后迟到测量数据只记迟到事件，不改动业务状态。
     */
    @Transactional
    public String recordMeasurement(long dispensingId, String payload) {
        Dispensing dispensing = dispensings.findDispensingById(dispensingId)
                .orElseThrow(() -> new NotFoundException("分装批次不存在: " + dispensingId));
        if (dispensing.status() != DispensingStatus.DISPENSED) {
            throw new BusinessException("分装批次未处于发药完成终态，测量数据应走正常流程: " + dispensing.status());
        }
        dispensings.insertLateEvent(dispensingId, "LATE_MEASUREMENT", payload,
                clock.instant().getEpochSecond());
        return "LATE_RECORDED";
    }

    /**
     * 勾稽台账复算：按衰变公式由母液、分装与核对链原始数据重新计算。
     * 已发药批次按台账测量时刻复算；未发药批次按当前时刻试算。
     */
    @Transactional(readOnly = true)
    public ReconciliationLedger recompute(long dispensingId) {
        Dispensing dispensing = dispensings.findDispensingById(dispensingId)
                .orElseThrow(() -> new NotFoundException("分装批次不存在: " + dispensingId));
        MotherLiquor mother = motherLiquors.findById(dispensing.motherLiquorId()).orElseThrow();
        Nuclide nuclide = nuclides.findById(mother.nuclideId()).orElseThrow();
        // 已落台账的批次按台账测量时刻复算，保证与台账逐项可比；否则按当前时刻试算
        long measuredAt = dispensings.findLedger(dispensingId)
                .map(ReconciliationLedger::measuredAtEpoch)
                .orElse(dispensing.dispensedAtEpoch() != null
                        ? dispensing.dispensedAtEpoch() : clock.instant().getEpochSecond());
        double remaining = decay.activityAt(mother.remainingActivityBq(), mother.remainingTimeEpoch(),
                measuredAt, nuclide.halfLifeSeconds());
        return reconcile(dispensing, mother, nuclide, remaining, measuredAt);
    }

    /**
     * 勾稽：初始活度 = 分装总量 + 剩余活度 + 衰变残量（全部推算至 measuredAtEpoch）。
     */
    private ReconciliationLedger reconcile(Dispensing dispensing, MotherLiquor mother, Nuclide nuclide,
                                           double remainingActivityBq, long measuredAtEpoch) {
        long halfLife = nuclide.halfLifeSeconds();
        double decayResidual = decay.decayResidual(mother.initialActivityBq(),
                mother.referenceTimeEpoch(), measuredAtEpoch, halfLife);
        double dispensedTotal = 0.0;
        List<Dispensing> actives = dispensings.findActiveByMotherLiquor(mother.id());
        for (Dispensing active : actives) {
            if (active.dispensedActivityBq() == null) {
                continue;
            }
            Prescription p = prescriptions.findById(active.prescriptionId()).orElseThrow();
            dispensedTotal += decay.activityAt(active.dispensedActivityBq(),
                    p.administrationTimeEpoch(), measuredAtEpoch, halfLife);
        }
        double lhs = mother.initialActivityBq();
        double rhs = dispensedTotal + remainingActivityBq + decayResidual;
        boolean balanced = Math.abs(lhs - rhs) <= BALANCE_RELATIVE_TOLERANCE * Math.max(1.0, Math.abs(lhs));
        return new ReconciliationLedger(0L, dispensing.id(), lhs, dispensedTotal,
                remainingActivityBq, decayResidual, balanced, measuredAtEpoch);
    }

    private Dispensing replayOrConflict(Dispensing existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new ConflictException("相同 requestId 但业务字段不一致，拒绝重复受理: " + existing.requestId());
        }
        return existing;
    }

    /**
     * 发药结果。replayed 为 true 表示命中幂等重放或终态重复完成，未发生新的状态变更与扣减。
     */
    public record DispenseResult(Dispensing dispensing, ReconciliationLedger ledger, boolean replayed) {
    }
}
