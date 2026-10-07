package com.example.starter.radio.service;

import java.time.Clock;
import java.util.List;

import com.example.starter.radio.domain.ChangeOrderStatus;
import com.example.starter.radio.domain.DispensingStatus;
import com.example.starter.radio.model.ChangeCheck;
import com.example.starter.radio.model.ChangeOrder;
import com.example.starter.radio.model.Dispensing;
import com.example.starter.radio.model.DispensingVersion;
import com.example.starter.radio.model.MotherLiquor;
import com.example.starter.radio.model.Nuclide;
import com.example.starter.radio.model.Prescription;
import com.example.starter.radio.repository.ChangeOrderRepository;
import com.example.starter.radio.repository.DispensingRepository;
import com.example.starter.radio.repository.MotherLiquorRepository;
import com.example.starter.radio.repository.NuclideRepository;
import com.example.starter.radio.repository.PrescriptionRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 给药时刻变更（合成数据软件验证，不用于真实给药决策）。
 * 变更只引用“已计算但尚未发药”的分装、当前处方版本与新的 UTC 给药时刻（须晚于母液参考时刻）；
 * 按原合成核素半衰期与原母液快照重算目标活度，仍须满足原 ±10% 处方偏差与库存校验。
 * 提交只形成候选版本，不立即改有效分装；两名不同人员共同复核后才在同一事务内生效：
 * 替换给药时刻（新增处方版本）与计算版本、清除旧双人核对有效性，旧计算与签认全部保留。
 * 候选期间原版本仍可合法发药；批准与发药竞争仅一方先提交（先分装行后候选行统一加锁）。
 * 所有写操作幂等：同键同参重放首次冻结结果，同键异参 409，失败不占键。
 */
@Service
public class ChangeOrderService {

    private final ChangeOrderRepository changeOrders;
    private final DispensingRepository dispensings;
    private final PrescriptionRepository prescriptions;
    private final MotherLiquorRepository motherLiquors;
    private final NuclideRepository nuclides;
    private final DecayService decay;
    private final Clock clock;

    public ChangeOrderService(ChangeOrderRepository changeOrders,
                              DispensingRepository dispensings,
                              PrescriptionRepository prescriptions,
                              MotherLiquorRepository motherLiquors,
                              NuclideRepository nuclides,
                              DecayService decay,
                              Clock clock) {
        this.changeOrders = changeOrders;
        this.dispensings = dispensings;
        this.prescriptions = prescriptions;
        this.motherLiquors = motherLiquors;
        this.nuclides = nuclides;
        this.decay = decay;
        this.clock = clock;
    }

    /**
     * 提交变更候选。
     *
     * @param requestId              幂等键
     * @param dispensingId           被变更分装
     * @param expectedVersion        客户端持有的分装版本（从 1 开始），与库内不一致 409
     * @param expectedPrescriptionVersion 客户端持有的当前处方版本号，与库内不一致 409
     * @param newAdministrationTimeEpoch 新的 UTC 给药时刻（epoch 秒），须晚于母液参考时刻
     */
    @Transactional
    public SubmitResult submit(String requestId, long dispensingId, int expectedVersion,
                               int expectedPrescriptionVersion, long newAdministrationTimeEpoch) {
        // 幂等重放：命中已冻结的提交结果，同参返回首次结果，异参 409；与业务校验同事务读取
        String fingerprint = Fingerprints.structured("SUBMIT", requestId, dispensingId, expectedVersion,
                expectedPrescriptionVersion, newAdministrationTimeEpoch);
        var existing = changeOrders.findBySubmitRequestId(requestId);
        if (existing.isPresent()) {
            return new SubmitResult(replayOrConflict(existing.get(), fingerprint), true);
        }

        // 先锁分装行，串行化并发提交与发药/生效
        Dispensing dispensing = dispensings.findDispensingByIdForUpdate(dispensingId)
                .orElseThrow(() -> new NotFoundException("分装批次不存在: " + dispensingId));
        // 持锁后再次检查幂等键：并发同键提交在首个事务提交后于此重放首次冻结结果
        var raced = changeOrders.findBySubmitRequestId(requestId);
        if (raced.isPresent()) {
            return new SubmitResult(replayOrConflict(raced.get(), fingerprint), true);
        }
        if (dispensing.status() == DispensingStatus.DISPENSED) {
            throw new BusinessException("分装已发药，历史不可重开，禁止提交变更");
        }
        if (dispensing.status() == DispensingStatus.REJECTED) {
            throw new BusinessException("分装已拒绝，禁止提交变更");
        }
        if (dispensing.status() == DispensingStatus.VOID) {
            throw new BusinessException("分装已撤回作废（VOID 终态），禁止改时刻");
        }
        if (expectedVersion != dispensing.versionNo()) {
            throw new ConflictException("分装版本已变化: expected=" + expectedVersion
                    + " actual=" + dispensing.versionNo());
        }
        Prescription current = prescriptions.findById(dispensing.prescriptionId()).orElseThrow();
        Prescription latest = prescriptions.findLatestByRoot(rootOf(current)).orElse(current);
        if (dispensing.prescriptionId() != latest.id()
                || expectedPrescriptionVersion != latest.versionNo()) {
            throw new ConflictException("处方版本已变化或引用非当前版本: expected=" + expectedPrescriptionVersion
                    + " current=" + latest.versionNo());
        }
        if (changeOrders.findActiveByDispensingForUpdate(dispensingId).isPresent()) {
            throw new BusinessException("该分装已存在待复核变更候选，须先生效或作废后再提交");
        }

        MotherLiquor mother = motherLiquors.findById(dispensing.motherLiquorId())
                .orElseThrow(() -> new NotFoundException("母液批次不存在: " + dispensing.motherLiquorId()));
        Nuclide nuclide = nuclides.findById(mother.nuclideId()).orElseThrow();
        if (newAdministrationTimeEpoch <= mother.referenceTimeEpoch()) {
            throw new BusinessException("新给药时刻须晚于母液参考时刻");
        }
        if (newAdministrationTimeEpoch == latest.administrationTimeEpoch()) {
            throw new BusinessException("新给药时刻与当前给药时刻相同，无变更");
        }

        // 按原合成核素半衰期与原母液快照（初始活度@参考时刻）重算目标活度
        double candidateActivity = decay.activityAt(mother.initialActivityBq(), mother.referenceTimeEpoch(),
                newAdministrationTimeEpoch, nuclide.halfLifeSeconds());
        double deviation = (candidateActivity - latest.prescribedActivityBq()) / latest.prescribedActivityBq();
        boolean withinTolerance = Math.abs(deviation) <= DispensingService.DISPENSE_TOLERANCE;
        // 库存校验：新时刻候选活度不得超过母液当前剩余活度推算至该时刻的可用活度。
        // 该校验只读快照，不扣减母液；且按原母液初始快照重算，成功重放不受随后剩余量变化影响。
        double availableAtNewTime = decay.activityAt(mother.remainingActivityBq(), mother.remainingTimeEpoch(),
                newAdministrationTimeEpoch, nuclide.halfLifeSeconds());
        boolean inventoryOk = candidateActivity <= availableAtNewTime + 1e-9 * Math.max(1.0, availableAtNewTime);

        boolean legal = withinTolerance && inventoryOk;
        String calcStatus = legal ? "LEGAL" : "REJECTED";
        Double storedActivity = legal ? candidateActivity : null;
        ChangeOrderStatus orderStatus = legal ? ChangeOrderStatus.PENDING : ChangeOrderStatus.REJECTED;
        int candidateVersionNo = dispensing.versionNo() + 1;

        long now = clock.instant().getEpochSecond();
        // 提交阶段不新增处方版本行：候选 pending 期间当前处方版本保持不变，原版本仍可合法发药。
        // 新处方版本在双人复核通过、生效的同一事务内创建（见 review）。
        try {
            long id = changeOrders.insert(requestId, fingerprint, dispensingId, expectedVersion,
                    latest.versionNo(), null, newAdministrationTimeEpoch, candidateVersionNo,
                    storedActivity, deviation, calcStatus, orderStatus, now);
            if (legal) {
                dispensings.markActiveChangeOrder(dispensingId, id, now);
            }
            ChangeOrder saved = changeOrders.findById(id).orElseThrow();
            return new SubmitResult(saved, false);
        } catch (DuplicateKeyException e) {
            return changeOrders.findBySubmitRequestId(requestId)
                    .map(d -> new SubmitResult(replayOrConflict(d, fingerprint), true))
                    .orElseThrow(() -> new IllegalStateException("变更候选写入冲突后读取失败", e));
        }
    }

    /**
     * 双人复核。两名不同人员先后复核，单人重复复核 409；仅 PENDING 且计算合法候选可复核。
     * 第二名不同人员复核通过即同事务生效。
     */
    @Transactional
    public ChangeOrder review(long changeOrderId, String reviewer) {
        ChangeOrder read = changeOrders.findById(changeOrderId)
                .orElseThrow(() -> new NotFoundException("变更单不存在: " + changeOrderId));
        // 统一锁顺序：先分装行后候选行，与发药竞争路径一致，避免死锁
        Dispensing dispensing = dispensings.findDispensingByIdForUpdate(read.dispensingId())
                .orElseThrow(() -> new NotFoundException("分装批次不存在: " + read.dispensingId()));
        ChangeOrder order = changeOrders.findByIdForUpdate(changeOrderId)
                .orElseThrow(() -> new NotFoundException("变更单不存在: " + changeOrderId));
        if (order.status() != ChangeOrderStatus.PENDING) {
            throw new BusinessException("变更单不处于待复核状态，禁止复核: " + order.status());
        }
        long now = clock.instant().getEpochSecond();
        if (order.firstReviewer() == null) {
            changeOrders.updateReviewer(changeOrderId, "first_reviewer", reviewer, now);
            changeOrders.insertCheck(changeOrderId, reviewer, 1, now);
            return changeOrders.findById(changeOrderId).orElseThrow();
        }
        if (order.firstReviewer().equals(reviewer)) {
            throw new ConflictException("变更复核须为两名不同人员，单人重复复核不生效: " + reviewer);
        }
        if (order.secondReviewer() != null) {
            throw new BusinessException("变更双人复核已完成，禁止重复复核");
        }

        // 已持分装行锁；若原版本已抢先提交发药（status=DISPENSED），变更生效拒绝。
        if (dispensing.status() == DispensingStatus.DISPENSED) {
            throw new BusinessException("原版本已先行发药，变更生效拒绝");
        }
        if (dispensing.versionNo() + 1 != order.candidateVersionNo()
                || dispensing.activeChangeOrderId() == null
                || dispensing.activeChangeOrderId() != order.id()) {
            throw new BusinessException("分装版本与候选不一致，变更生效拒绝");
        }

        // 同事务：创建新处方版本（旧版本保留）→ 候选置 APPROVED → 写入新计算版本证据
        // → 替换有效分装（时刻+活度+版本），清除旧双人核对有效性（first/second 置空、状态回落 CALCULATED）。
        // 旧版本行与旧签认行保留。
        Prescription base = prescriptions.findById(dispensing.prescriptionId()).orElseThrow();
        int newPrescriptionVersionNo = order.prescriptionVersion() + 1;
        String versionedCode = base.code() + "#v" + newPrescriptionVersionNo;
        long newPrescriptionId = prescriptions.insertVersion(versionedCode, rootOf(base), base.nuclideId(),
                base.prescribedActivityBq(), order.newAdministrationTimeEpoch(), newPrescriptionVersionNo, now);
        changeOrders.insertCheck(changeOrderId, reviewer, 2, now);
        changeOrders.markApproved(changeOrderId, reviewer, newPrescriptionId, now);
        dispensings.insertVersion(dispensing.id(), order.candidateVersionNo(), newPrescriptionId,
                order.newAdministrationTimeEpoch(), order.candidateActivityBq(), order.candidateDeviationRatio(),
                "LEGAL", changeOrderId, now);
        dispensings.applyNewVersion(dispensing.id(), newPrescriptionId, order.candidateActivityBq(),
                order.candidateDeviationRatio(), order.candidateVersionNo(), now);
        return changeOrders.findById(changeOrderId).orElseThrow();
    }

    /**
     * 作废候选（幂等）。仅 PENDING 可作废；已生效/已失效/已拒绝不可作废。同键同参重放，异参 409。
     */
    @Transactional
    public ChangeOrder voidOrder(String requestId, long changeOrderId) {
        var byVoidKey = changeOrders.findByVoidRequestId(requestId);
        if (byVoidKey.isPresent()) {
            ChangeOrder o = byVoidKey.get();
            if (o.id() != changeOrderId) {
                throw new ConflictException("相同作废 requestId 引用不同变更单: " + requestId);
            }
            return o;
        }

        ChangeOrder read = changeOrders.findById(changeOrderId)
                .orElseThrow(() -> new NotFoundException("变更单不存在: " + changeOrderId));
        // 统一锁顺序：先分装行后候选行
        Dispensing dispensing = dispensings.findDispensingByIdForUpdate(read.dispensingId()).orElseThrow();
        ChangeOrder order = changeOrders.findByIdForUpdate(changeOrderId)
                .orElseThrow(() -> new NotFoundException("变更单不存在: " + changeOrderId));
        if (changeOrders.findBySubmitRequestId(requestId)
                .map(o -> o.id() != changeOrderId)
                .orElse(false)) {
            throw new ConflictException("requestId 已被用作其它变更单的提交键: " + requestId);
        }
        if (order.status() != ChangeOrderStatus.PENDING) {
            throw new BusinessException("变更单不处于待复核状态，禁止作废: " + order.status());
        }
        long now = clock.instant().getEpochSecond();
        changeOrders.markVoid(changeOrderId, requestId, now);
        if (dispensing.activeChangeOrderId() != null && dispensing.activeChangeOrderId() == order.id()) {
            dispensings.markActiveChangeOrder(dispensing.id(), null, now);
        }
        return changeOrders.findById(changeOrderId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public ChangeOrder getOrder(long changeOrderId) {
        return changeOrders.findById(changeOrderId)
                .orElseThrow(() -> new NotFoundException("变更单不存在: " + changeOrderId));
    }

    /**
     * 版本计算证据查询：返回分装全部不可变计算版本（初始 + 历次变更）。
     */
    @Transactional(readOnly = true)
    public List<DispensingVersion> versions(long dispensingId) {
        if (dispensings.findDispensingById(dispensingId).isEmpty()) {
            throw new NotFoundException("分装批次不存在: " + dispensingId);
        }
        return dispensings.findVersions(dispensingId);
    }

    /**
     * 变更单及其复核链证据查询。
     */
    @Transactional(readOnly = true)
    public ChangeEvidence evidence(long changeOrderId) {
        ChangeOrder order = getOrder(changeOrderId);
        return new ChangeEvidence(order, changeOrders.findChecks(changeOrderId));
    }

    private long rootOf(Prescription p) {
        return p.rootPrescriptionId() == null ? p.id() : p.rootPrescriptionId();
    }

    private ChangeOrder replayOrConflict(ChangeOrder existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new ConflictException("相同 requestId 但业务字段不一致，拒绝重复受理: " + existing.requestId());
        }
        return existing;
    }

    /** 提交结果。replayed 为 true 表示命中幂等重放，未产生新候选与新版本行。 */
    public record SubmitResult(ChangeOrder order, boolean replayed) {
    }

    /** 变更单证据：候选单与其双人复核链。 */
    public record ChangeEvidence(ChangeOrder order, List<ChangeCheck> reviews) {
    }
}
