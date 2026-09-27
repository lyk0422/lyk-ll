package com.example.starter.radio.service;

import java.time.Clock;
import java.util.List;

import com.example.starter.radio.domain.DispensingStatus;
import com.example.starter.radio.domain.DispensingVersionStatus;
import com.example.starter.radio.domain.DoseChangeStatus;
import com.example.starter.radio.model.Dispensing;
import com.example.starter.radio.model.DispensingVersion;
import com.example.starter.radio.model.DoseChangeCheck;
import com.example.starter.radio.model.DoseChangeOrder;
import com.example.starter.radio.model.IdempotentRequest;
import com.example.starter.radio.model.MotherLiquor;
import com.example.starter.radio.model.Nuclide;
import com.example.starter.radio.model.Prescription;
import com.example.starter.radio.repository.DispensingRepository;
import com.example.starter.radio.repository.DispensingVersionRepository;
import com.example.starter.radio.repository.DoseChangeOrderRepository;
import com.example.starter.radio.repository.IdempotentRequestRepository;
import com.example.starter.radio.repository.MotherLiquorRepository;
import com.example.starter.radio.repository.NuclideRepository;
import com.example.starter.radio.repository.PrescriptionRepository;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 未发药分装的给药时刻变更复核流程。
 *
 * <p>候选提交：仅允许引用“已计算但尚未发药”的分装、当前处方版本与晚于母液参考时刻的新 UTC 给药时刻；
 * 按原合成核素半衰期与原母液初始活度快照重算目标活度，仍须满足原 ±10% 处方偏差，并通过库存校验。
 * 候选形成时不改有效分装；两名不同人员复核齐备后，在同一事务内替换给药时刻与计算版本、
 * 将旧版本置 SUPERSEDED、清空旧双人核对有效性（旧计算与签认行保留）。
 *
 * <p>并发：候选待决期间允许原版本合法发药；批准与发药只可一方先提交。
 * 发药先成则候选在发药事务内置 REJECTED；变更先成则旧版本发药因回到未核对状态而 422。
 *
 * <p>幂等：新增写操作以 requestId 携带完整参数（含版本与精确时刻）计算结构化指纹，
 * 业务、版本与幂等冻结结果同事务；同键同参重放首次冻结结果，异参 409，失败回滚不占键。
 */
@Service
public class DoseChangeService {

    public static final String OP_SUBMIT = "SUBMIT_CHANGE";
    public static final String OP_REVIEW = "REVIEW_CHANGE";
    public static final String OP_CANCEL = "CANCEL_CHANGE";

    private static final double TOLERANCE = DispensingService.DISPENSE_TOLERANCE;

    private final DispensingRepository dispensings;
    private final DispensingVersionRepository versions;
    private final DoseChangeOrderRepository changeOrders;
    private final IdempotentRequestRepository idempotency;
    private final MotherLiquorRepository motherLiquors;
    private final PrescriptionRepository prescriptions;
    private final NuclideRepository nuclides;
    private final DecayService decay;
    private final Clock clock;
    private final JsonMapper json = new JsonMapper();

    public DoseChangeService(DispensingRepository dispensings,
                             DispensingVersionRepository versions,
                             DoseChangeOrderRepository changeOrders,
                             IdempotentRequestRepository idempotency,
                             MotherLiquorRepository motherLiquors,
                             PrescriptionRepository prescriptions,
                             NuclideRepository nuclides,
                             DecayService decay,
                             Clock clock) {
        this.dispensings = dispensings;
        this.versions = versions;
        this.changeOrders = changeOrders;
        this.idempotency = idempotency;
        this.motherLiquors = motherLiquors;
        this.prescriptions = prescriptions;
        this.nuclides = nuclides;
        this.decay = decay;
        this.clock = clock;
    }

    /**
     * 变更流程结果。phase 标识本次所处阶段；replayed 为 true 表示命中幂等冻结，未发生新的状态变更。
     */
    public record ChangeOutcome(DoseChangeOrder order, Dispensing dispensing, String phase, boolean replayed) {
    }

    /**
     * 提交给药时刻变更候选。expectedVersion 须等于分装当前版本；不满足 409。
     */
    @Transactional
    public ChangeOutcome submit(String requestId, long dispensingId, int expectedVersion,
                                long newAdministrationTimeEpoch) {
        String fingerprint = Fingerprints.structured(List.of(
                OP_SUBMIT, requestId, Long.toString(dispensingId), Integer.toString(expectedVersion),
                Long.toString(newAdministrationTimeEpoch)));

        var frozen = idempotency.findByRequestId(requestId);
        if (frozen.isPresent()) {
            return replay(frozen.get(), fingerprint);
        }

        // 先锁分装，统一与复核/发药的加锁顺序（分装 → 变更单），避免死锁
        Dispensing dispensing = dispensings.findDispensingByIdForUpdate(dispensingId)
                .orElseThrow(() -> new NotFoundException("分装批次不存在: " + dispensingId));
        rejectIfDispensedOrRejected(dispensing);
        if (dispensing.version() != expectedVersion) {
            throw new ConflictException(String.format(
                    "expectedVersion 与分装当前版本不一致: expected=%d, actual=%d",
                    expectedVersion, dispensing.version()));
        }

        MotherLiquor mother = motherLiquors.findById(dispensing.motherLiquorId()).orElseThrow();
        Nuclide nuclide = nuclides.findById(mother.nuclideId()).orElseThrow();
        Prescription prescription = prescriptions.findById(dispensing.prescriptionId()).orElseThrow();
        // 原始计算版本（version=1）固化母液/核素快照，重算一律以该快照为准
        DispensingVersion initial = versions.findByDispensingAndVersion(dispensingId, 1).orElseThrow();

        if (newAdministrationTimeEpoch <= mother.referenceTimeEpoch()) {
            throw new IllegalArgumentException("新的给药时刻必须晚于母液参考时刻");
        }

        double candidateActivity = decay.activityAt(initial.motherInitialActivityBq(),
                initial.motherReferenceTimeEpoch(), newAdministrationTimeEpoch,
                initial.halfLifeSeconds());
        double candidateDeviation = (candidateActivity - prescription.prescribedActivityBq())
                / prescription.prescribedActivityBq();
        if (Math.abs(candidateDeviation) > TOLERANCE) {
            throw new BusinessException(String.format(
                    "候选活度与处方偏差 %.4f 超出 ±10%%，拒绝变更候选", candidateDeviation));
        }

        // 库存校验：母液当前剩余活度推算至新给药时刻后仍须足以覆盖候选活度；候选阶段不扣减
        double inventoryAvailable = decay.activityAt(mother.remainingActivityBq(),
                mother.remainingTimeEpoch(), newAdministrationTimeEpoch, nuclide.halfLifeSeconds());
        if (inventoryAvailable < candidateActivity) {
            throw new BusinessException(String.format(
                    "库存校验失败：新给药时刻可用活度 %.6f 低于候选活度 %.6f",
                    inventoryAvailable, candidateActivity));
        }

        changeOrders.findPendingByDispensingForUpdate(dispensingId).ifPresent(p -> {
            throw new ConflictException("该分装已存在待决变更候选: changeOrderId=" + p.id());
        });

        long now = clock.instant().getEpochSecond();
        DoseChangeOrder order = new DoseChangeOrder(0L, requestId, fingerprint, dispensingId, expectedVersion,
                prescription.id(), newAdministrationTimeEpoch, expectedVersion + 1, candidateActivity,
                candidateDeviation, initial.motherInitialActivityBq(), initial.motherReferenceTimeEpoch(),
                initial.halfLifeSeconds(), inventoryAvailable, DoseChangeStatus.PENDING,
                null, null, now, null);
        long orderId;
        try {
            orderId = changeOrders.insert(order);
            // 业务、版本与幂等结果同事务；后续失败整体回滚，因而失败不占用 requestId
            idempotency.insert(new IdempotentRequest(requestId, fingerprint, OP_SUBMIT,
                    freeze("SUBMITTED", orderId), now));
        } catch (DuplicateKeyException e) {
            // 同键并发：重读冻结结果按重放/异参处理；否则是待决槽被另一候选抢占 → 409
            IdempotentRequest other = idempotency.findByRequestId(requestId).orElse(null);
            if (other != null) {
                return replay(other, fingerprint);
            }
            throw new ConflictException("该分装已存在待决变更候选（并发受理）: dispensingId=" + dispensingId);
        }
        DoseChangeOrder saved = changeOrders.findById(orderId).orElseThrow();
        return new ChangeOutcome(saved, dispensing, "SUBMITTED", false);
    }

    /**
     * 双人复核：两名不同人员先后复核；第二签在同一事务内触发生效。
     */
    @Transactional
    public ChangeOutcome review(String requestId, long changeOrderId, String checker) {
        String fingerprint = Fingerprints.structured(List.of(
                OP_REVIEW, requestId, Long.toString(changeOrderId), checker));

        var frozen = idempotency.findByRequestId(requestId);
        if (frozen.isPresent()) {
            return replay(frozen.get(), fingerprint);
        }

        DoseChangeOrder loaded = changeOrders.findById(changeOrderId)
                .orElseThrow(() -> new NotFoundException("变更候选不存在: " + changeOrderId));
        // 统一加锁顺序：先分装后变更单
        Dispensing dispensing = dispensings.findDispensingByIdForUpdate(loaded.dispensingId()).orElseThrow();
        DoseChangeOrder order = changeOrders.findByIdForUpdate(changeOrderId).orElseThrow();
        long now = clock.instant().getEpochSecond();

        String phase;
        if (order.status() == DoseChangeStatus.REJECTED) {
            // 发药先成已在发药事务内将候选置 REJECTED：晚到的复核不再改状态，但仍冻结结果供同键重放
            phase = "REJECTED";
        } else if (order.status() != DoseChangeStatus.PENDING) {
            throw new BusinessException("变更候选已终局，禁止复核: " + order.status());
        } else if (order.firstReviewer() == null) {
            changeOrders.updateReviewer(changeOrderId, "first_reviewer", checker);
            changeOrders.insertCheck(changeOrderId, checker, 1, now);
            phase = "FIRST_REVIEWED";
        } else if (order.firstReviewer().equals(checker)) {
            throw new ConflictException("变更复核须两名不同人员，单人重复复核不生效: " + checker);
        } else if (order.secondReviewer() != null) {
            throw new BusinessException("变更双人复核已完成");
        } else {
            changeOrders.updateReviewer(changeOrderId, "second_reviewer", checker);
            changeOrders.insertCheck(changeOrderId, checker, 2, now);
            phase = approveOrReject(order, dispensing, now);
        }

        try {
            idempotency.insert(new IdempotentRequest(requestId, fingerprint, OP_REVIEW,
                    freeze(phase, changeOrderId), now));
        } catch (DuplicateKeyException e) {
            return replay(idempotency.findByRequestId(requestId).orElseThrow(() -> e), fingerprint);
        }
        DoseChangeOrder saved = changeOrders.findById(changeOrderId).orElseThrow();
        Dispensing current = dispensings.findDispensingById(dispensing.id()).orElseThrow();
        return new ChangeOutcome(saved, current, phase, false);
    }

    /**
     * 作废待决候选。已终局候选再次作废：CANCELLED 幂等成功，其余终局 422。
     */
    @Transactional
    public ChangeOutcome cancel(String requestId, long changeOrderId) {
        String fingerprint = Fingerprints.structured(List.of(
                OP_CANCEL, requestId, Long.toString(changeOrderId)));

        var frozen = idempotency.findByRequestId(requestId);
        if (frozen.isPresent()) {
            return replay(frozen.get(), fingerprint);
        }

        DoseChangeOrder loaded = changeOrders.findById(changeOrderId)
                .orElseThrow(() -> new NotFoundException("变更候选不存在: " + changeOrderId));
        Dispensing dispensing = dispensings.findDispensingByIdForUpdate(loaded.dispensingId()).orElseThrow();
        DoseChangeOrder order = changeOrders.findByIdForUpdate(changeOrderId).orElseThrow();
        long now = clock.instant().getEpochSecond();

        String phase;
        if (order.status() == DoseChangeStatus.PENDING) {
            changeOrders.decide(changeOrderId, DoseChangeStatus.CANCELLED, now);
            phase = "CANCELLED";
        } else if (order.status() == DoseChangeStatus.CANCELLED) {
            phase = "CANCELLED";
        } else {
            throw new BusinessException("变更候选已终局，禁止作废: " + order.status());
        }

        try {
            idempotency.insert(new IdempotentRequest(requestId, fingerprint, OP_CANCEL,
                    freeze(phase, changeOrderId), now));
        } catch (DuplicateKeyException e) {
            return replay(idempotency.findByRequestId(requestId).orElseThrow(() -> e), fingerprint);
        }
        DoseChangeOrder saved = changeOrders.findById(changeOrderId).orElseThrow();
        return new ChangeOutcome(saved, dispensing, phase, false);
    }

    /**
     * 版本计算证据：某分装的全部计算版本（旧版本 SUPERSEDED 保留）。
     */
    @Transactional(readOnly = true)
    public List<DispensingVersion> versions(long dispensingId) {
        dispensings.findDispensingById(dispensingId)
                .orElseThrow(() -> new NotFoundException("分装批次不存在: " + dispensingId));
        return versions.findByDispensing(dispensingId);
    }

    /**
     * 变更候选证据：某分装的全部候选单（含终局）。
     */
    @Transactional(readOnly = true)
    public List<DoseChangeOrder> changeOrders(long dispensingId) {
        dispensings.findDispensingById(dispensingId)
                .orElseThrow(() -> new NotFoundException("分装批次不存在: " + dispensingId));
        return changeOrders.findByDispensing(dispensingId);
    }

    /**
     * 变更候选双人复核链证据。
     */
    @Transactional(readOnly = true)
    public List<DoseChangeCheck> changeChecks(long changeOrderId) {
        changeOrders.findById(changeOrderId)
                .orElseThrow(() -> new NotFoundException("变更候选不存在: " + changeOrderId));
        return changeOrders.findChecks(changeOrderId);
    }

    /**
     * 第二签生效判定：发药先成则拒绝变更；否则同事务替换版本。
     */
    private String approveOrReject(DoseChangeOrder order, Dispensing dispensing, long now) {
        // 发药事务会先锁分装并把待决候选置 REJECTED；此处持分装锁读到的状态即竞争裁决结果
        if (dispensing.status() == DispensingStatus.DISPENSED
                || dispensing.version() != order.expectedVersion()) {
            changeOrders.decide(order.id(), DoseChangeStatus.REJECTED, now);
            return "REJECTED";
        }
        int newVersion = order.candidateVersion();
        // 旧版本置 SUPERSEDED（证据保留），插入新版本证据
        versions.markSuperseded(dispensing.id(), order.expectedVersion());
        versions.insert(new DispensingVersion(0L, dispensing.id(), newVersion,
                order.newAdministrationTimeEpoch(), order.candidateActivityBq(),
                order.candidateDeviationRatio(), dispensing.motherLiquorId(),
                order.motherInitialActivityBq(), order.motherReferenceTimeEpoch(),
                order.halfLifeSeconds(), "CHANGE", order.id(),
                DispensingVersionStatus.ACTIVE, now), now);
        // 同事务替换给药时刻与计算版本、清空旧双人核对有效性
        dispensings.applyNewVersion(dispensing.id(), newVersion, order.newAdministrationTimeEpoch(),
                order.candidateActivityBq(), order.candidateDeviationRatio(), now);
        changeOrders.decide(order.id(), DoseChangeStatus.APPROVED, now);
        return "APPROVED";
    }

    private void rejectIfDispensedOrRejected(Dispensing dispensing) {
        if (dispensing.status() == DispensingStatus.DISPENSED) {
            throw new BusinessException("分装已发药，历史绝不重开，禁止变更: dispensingId=" + dispensing.id());
        }
        if (dispensing.status() == DispensingStatus.REJECTED) {
            throw new BusinessException("分装已被拒绝，禁止变更: dispensingId=" + dispensing.id());
        }
    }

    private ChangeOutcome replay(IdempotentRequest existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new ConflictException("相同 requestId 但业务字段不一致，拒绝重复受理: " + existing.requestId());
        }
        FrozenSnapshot snapshot = unfreeze(existing.resultSnapshot());
        DoseChangeOrder order = changeOrders.findById(snapshot.orderId()).orElseThrow();
        Dispensing dispensing = dispensings.findDispensingById(order.dispensingId()).orElseThrow();
        return new ChangeOutcome(order, dispensing, snapshot.phase(), true);
    }

    private String freeze(String phase, long orderId) {
        try {
            return json.writeValueAsString(new FrozenSnapshot(phase, orderId));
        } catch (Exception e) {
            throw new IllegalStateException("幂等快照序列化失败", e);
        }
    }

    private FrozenSnapshot unfreeze(String jsonText) {
        try {
            return json.readValue(jsonText, FrozenSnapshot.class);
        } catch (Exception e) {
            throw new IllegalStateException("幂等快照反序列化失败", e);
        }
    }

    /** 幂等冻结快照：仅固化首次结果阶段与候选单主键，业务明细由证据表重读。 */
    private record FrozenSnapshot(String phase, long orderId) {
    }
}
