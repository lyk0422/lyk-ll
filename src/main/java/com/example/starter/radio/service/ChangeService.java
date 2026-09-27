package com.example.starter.radio.service;

import java.time.Clock;
import java.util.List;

import com.example.starter.radio.domain.ChangeStatus;
import com.example.starter.radio.domain.DispensingStatus;
import com.example.starter.radio.model.Dispensing;
import com.example.starter.radio.model.DispensingChange;
import com.example.starter.radio.model.DispensingVersion;
import com.example.starter.radio.model.MotherLiquor;
import com.example.starter.radio.model.Nuclide;
import com.example.starter.radio.model.Prescription;
import com.example.starter.radio.repository.ChangeRepository;
import com.example.starter.radio.repository.DispensingRepository;
import com.example.starter.radio.repository.MotherLiquorRepository;
import com.example.starter.radio.repository.NuclideRepository;
import com.example.starter.radio.repository.PrescriptionRepository;
import com.example.starter.radio.repository.VersionRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 未发药分装的给药时刻变更流程（仅合成数据，不用于真实给药决策）。
 *
 * <p>提交只形成 PENDING 候选，不改动有效分装；候选按原合成核素半衰期与原母液初始活度快照
 * 重算目标活度，仍须满足原 ±10% 处方偏差。两名不同人员复核完成时在同一事务内：
 * 固化新版本计算证据、替换有效分装的给药时刻/活度/版本号、状态回到待核对并清除旧双人核对有效性，
 * 旧版本计算与签认行全部保留。随后发药必须对新版本重新完成两名不同人员核对，旧签认不复用。
 *
 * <p>候选期间原版本仍可合法发药；生效复核与发药竞争只可一方先提交，由分装行锁串行化：
 * 发药先成则变更在复核完成时被拒绝（REJECTED 终态证据独立落库），变更先成则旧版本发药被拒。
 * 候选超差或母液库存校验失败直接拒绝，不扣减母液、不占用 requestId；
 * 成功重放返回首次冻结结果，不受随后母液剩余量变化影响。
 */
@Service
public class ChangeService {

    /** 候选版本与处方活度偏差阈值：绝对值不超过 10%（恰 10% 合法）。 */
    public static final double CHANGE_TOLERANCE = 0.10;

    private final DispensingRepository dispensings;
    private final ChangeRepository changes;
    private final VersionRepository versions;
    private final MotherLiquorRepository motherLiquors;
    private final PrescriptionRepository prescriptions;
    private final NuclideRepository nuclides;
    private final DecayService decay;
    private final Clock clock;

    public ChangeService(DispensingRepository dispensings,
                         ChangeRepository changes,
                         VersionRepository versions,
                         MotherLiquorRepository motherLiquors,
                         PrescriptionRepository prescriptions,
                         NuclideRepository nuclides,
                         DecayService decay,
                         Clock clock) {
        this.dispensings = dispensings;
        this.changes = changes;
        this.versions = versions;
        this.motherLiquors = motherLiquors;
        this.prescriptions = prescriptions;
        this.nuclides = nuclides;
        this.decay = decay;
        this.clock = clock;
    }

    /**
     * 提交给药时刻变更候选（幂等）。
     *
     * @param requestId                 幂等键；同键同参重放首次冻结结果，异参 409，校验失败不占键
     * @param dispensingId              被变更分装，须已计算且未发药
     * @param expectedVersion           客户端持有版本，须等于当前版本，否则 409
     * @param newAdministrationTimeEpoch 候选新给药时刻（UTC epoch 秒），须晚于母液参考时刻
     */
    @Transactional
    public DispensingChange submit(String requestId, long dispensingId, long expectedVersion,
                                   long newAdministrationTimeEpoch) {
        String fingerprint = Fingerprints.structured(requestId, dispensingId, expectedVersion,
                newAdministrationTimeEpoch);
        var existing = changes.findByRequestId(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }

        // 行锁串行化同一分装的候选提交、复核生效与发药
        Dispensing dispensing = dispensings.findDispensingByIdForUpdate(dispensingId)
                .orElseThrow(() -> new NotFoundException("分装批次不存在: " + dispensingId));
        if (dispensing.status() == DispensingStatus.DISPENSED) {
            throw new BusinessException("分装已发药，已发药历史不得重开变更: " + dispensingId);
        }
        if (dispensing.status() == DispensingStatus.REJECTED) {
            throw new BusinessException("分装计算已拒绝，不能提交变更: " + dispensingId);
        }
        if (dispensing.version() != expectedVersion) {
            throw new ConflictException("expectedVersion 与当前版本不一致: expected=" + expectedVersion
                    + ", current=" + dispensing.version());
        }
        if (changes.existsPendingByDispensing(dispensingId)) {
            throw new BusinessException("该分装已存在待复核的变更候选，须先复核或作废后才能再次提交");
        }

        MotherLiquor mother = motherLiquors.findById(dispensing.motherLiquorId())
                .orElseThrow(() -> new IllegalStateException("母液批次缺失: " + dispensing.motherLiquorId()));
        if (!"OPEN".equals(mother.status())) {
            // 库存校验失败：不写候选、不扣减母液、不占用 requestId
            throw new BusinessException("母液批次已完结，库存校验失败: " + mother.batchCode());
        }
        if (newAdministrationTimeEpoch <= mother.referenceTimeEpoch()) {
            throw new BusinessException("新给药时刻须晚于母液参考时刻: newTime=" + newAdministrationTimeEpoch
                    + ", reference=" + mother.referenceTimeEpoch());
        }
        Nuclide nuclide = nuclides.findById(mother.nuclideId())
                .orElseThrow(() -> new IllegalStateException("核素表缺少记录: " + mother.nuclideId()));
        Prescription prescription = prescriptions.findById(dispensing.prescriptionId())
                .orElseThrow(() -> new IllegalStateException("处方缺失: " + dispensing.prescriptionId()));

        // 按原半衰期与原母液初始活度快照重算（取版本1固化快照，与随后剩余量变化无关）
        DispensingVersion snapshot = versions.findByDispensingAndVersion(dispensingId, 1L)
                .orElseThrow(() -> new IllegalStateException("版本1计算证据缺失: " + dispensingId));
        double candidateActivity = decay.activityAt(snapshot.motherInitialActivityBq(),
                snapshot.motherReferenceTimeEpoch(), newAdministrationTimeEpoch, snapshot.halfLifeSeconds());
        double deviation = (candidateActivity - prescription.prescribedActivityBq())
                / prescription.prescribedActivityBq();
        if (Math.abs(deviation) > CHANGE_TOLERANCE) {
            // 超出偏差：不写候选、不扣减母液、不占用 requestId
            throw new BusinessException(String.format(
                    "候选活度与处方偏差超出 ±10%%，拒绝变更: 候选=%.6f 偏差=%.6f", candidateActivity, deviation));
        }

        long candidateVersionNo = dispensing.version() + 1;
        long now = clock.instant().getEpochSecond();
        try {
            long id = changes.insert(requestId, fingerprint, dispensingId, expectedVersion,
                    newAdministrationTimeEpoch, candidateVersionNo, candidateActivity, deviation, now);
            return changes.findById(id).orElseThrow();
        } catch (DuplicateKeyException e) {
            // 并发同键提交：唯一约束保证仅一条落库，其余按幂等重放/异参冲突处理
            return changes.findByRequestId(requestId)
                    .map(c -> replayOrConflict(c, fingerprint))
                    .orElseThrow(() -> new IllegalStateException("变更单写入冲突后读取失败", e));
        }
    }

    /**
     * 候选双人复核：两名不同人员先后复核；第二名复核完成即竞争判定并生效。
     * 与发药竞争失败（原版本已发药）时，候选在同一事务内置为 REJECTED 终态证据并返回 rejected=true，
     * 由调用方决定是否转译为 422；第二名复核链记录保留为证据。
     */
    @Transactional
    public ReviewResult review(long changeId, String checker) {
        DispensingChange change = changes.findByIdForUpdate(changeId)
                .orElseThrow(() -> new NotFoundException("变更单不存在: " + changeId));
        if (change.status() == ChangeStatus.APPROVED) {
            throw new BusinessException("变更候选已生效，禁止重复复核");
        }
        if (change.status() == ChangeStatus.VOID) {
            throw new BusinessException("变更候选已作废，禁止复核");
        }
        if (change.status() == ChangeStatus.REJECTED) {
            throw new BusinessException("变更候选已被拒绝（发药已先完成），禁止复核");
        }

        long now = clock.instant().getEpochSecond();
        if (change.firstChecker() == null) {
            changes.updateChecker(changeId, "first_checker", checker, now);
            changes.insertChangeCheck(changeId, checker, 1, now);
            return new ReviewResult(changes.findById(changeId).orElseThrow(), false);
        }
        if (change.firstChecker().equals(checker)) {
            throw new ConflictException("候选复核须为两名不同人员，单人重复复核不生效: " + checker);
        }
        if (change.secondChecker() != null) {
            throw new BusinessException("候选双人复核已完成，禁止重复复核");
        }

        // 第二名复核：记录第二人后进入竞争判定（分装行锁串行化发药）
        changes.updateChecker(changeId, "second_checker", checker, now);
        changes.insertChangeCheck(changeId, checker, 2, now);

        Dispensing dispensing = dispensings.findDispensingByIdForUpdate(change.dispensingId())
                .orElseThrow(() -> new NotFoundException("分装批次不存在: " + change.dispensingId()));
        if (dispensing.status() == DispensingStatus.DISPENSED
                || dispensing.version() != change.expectedVersion()) {
            // 发药先成（或版本已被其他变更推进）：变更拒绝，REJECTED 证据随本事务提交
            long rejectedAt = clock.instant().getEpochSecond();
            changes.markRejected(changeId, rejectedAt);
            return new ReviewResult(changes.findById(changeId).orElseThrow(), true);
        }

        DispensingVersion snapshot = versions.findByDispensingAndVersion(change.dispensingId(), 1L)
                .orElseThrow(() -> new IllegalStateException("版本1计算证据缺失: " + change.dispensingId()));
        double activity = decay.activityAt(snapshot.motherInitialActivityBq(),
                snapshot.motherReferenceTimeEpoch(), change.newAdministrationTimeEpoch(),
                snapshot.halfLifeSeconds());
        if (change.candidateActivityBq() == null
                || Math.abs(activity - change.candidateActivityBq()) > 1e-9 * Math.max(1.0, activity)
                || change.candidateDeviationRatio() == null
                || Math.abs(change.candidateDeviationRatio()) > CHANGE_TOLERANCE) {
            long rejectedAt = clock.instant().getEpochSecond();
            changes.markRejected(changeId, rejectedAt);
            return new ReviewResult(changes.findById(changeId).orElseThrow(), true);
        }

        // 生效同事务：固化新版本证据 → 替换有效分装版本 → 候选置为已生效；旧版本与签认保留
        long approvedAt = clock.instant().getEpochSecond();
        long newVersion = change.candidateVersionNo();
        versions.insert(new DispensingVersion(0L, change.dispensingId(), newVersion,
                change.newAdministrationTimeEpoch(), activity, change.candidateDeviationRatio(),
                snapshot.motherInitialActivityBq(), snapshot.motherReferenceTimeEpoch(),
                snapshot.halfLifeSeconds(), "CHANGE", approvedAt));
        dispensings.activateVersion(change.dispensingId(), newVersion,
                change.newAdministrationTimeEpoch(), activity, change.candidateDeviationRatio(), approvedAt);
        changes.markApproved(changeId, approvedAt);
        return new ReviewResult(changes.findById(changeId).orElseThrow(), false);
    }

    /**
     * 作废候选：仅 PENDING 可作废；已作废重放幂等成功，已生效/已拒绝禁止作废。
     */
    @Transactional
    public DispensingChange cancel(long changeId) {
        DispensingChange change = changes.findByIdForUpdate(changeId)
                .orElseThrow(() -> new NotFoundException("变更单不存在: " + changeId));
        if (change.status() == ChangeStatus.VOID) {
            return change;
        }
        if (change.status() != ChangeStatus.PENDING) {
            throw new BusinessException("变更候选已处于终态，禁止作废: " + change.status());
        }
        long now = clock.instant().getEpochSecond();
        changes.markVoid(changeId, now);
        return changes.findById(changeId).orElseThrow();
    }

    /** 分装的全部计算版本证据（版本1原始计算及历次生效变更，按版本号升序）。 */
    @Transactional(readOnly = true)
    public List<DispensingVersion> versions(long dispensingId) {
        dispensings.findDispensingById(dispensingId)
                .orElseThrow(() -> new NotFoundException("分装批次不存在: " + dispensingId));
        return versions.findByDispensing(dispensingId);
    }

    /** 分装的全部变更单证据（含候选与终态，按提交次序升序）。 */
    @Transactional(readOnly = true)
    public List<DispensingChange> changes(long dispensingId) {
        dispensings.findDispensingById(dispensingId)
                .orElseThrow(() -> new NotFoundException("分装批次不存在: " + dispensingId));
        return changes.findByDispensing(dispensingId);
    }

    /** 单个变更单证据查询。 */
    @Transactional(readOnly = true)
    public DispensingChange change(long changeId) {
        return changes.findById(changeId)
                .orElseThrow(() -> new NotFoundException("变更单不存在: " + changeId));
    }

    private DispensingChange replayOrConflict(DispensingChange existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new ConflictException("相同 requestId 但业务字段不一致，拒绝重复受理: " + existing.requestId());
        }
        return existing;
    }

    /**
     * 复核结果。rejected 为 true 表示第二名复核完成但竞争判定失败（原版本已发药），
     * 候选已落 REJECTED 终态；HTTP 层据此转译 422。
     */
    public record ReviewResult(DispensingChange change, boolean rejected) {
    }
}
