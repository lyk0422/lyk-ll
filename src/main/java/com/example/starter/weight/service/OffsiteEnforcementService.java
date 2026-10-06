package com.example.starter.weight.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.weight.api.ApiException;
import com.example.starter.weight.api.dto.DecisionResponse;
import com.example.starter.weight.api.dto.OffsiteWeighRequest;
import com.example.starter.weight.api.dto.OffsiteWeighResponse;
import com.example.starter.weight.domain.DecisionTier;
import com.example.starter.weight.domain.OffsiteRules;
import com.example.starter.weight.domain.OffsiteStatus;
import com.example.starter.weight.domain.OverloadRules;
import com.example.starter.weight.domain.PendingReason;
import com.example.starter.weight.repo.CaseRepository;
import com.example.starter.weight.repo.DecisionRepository;
import com.example.starter.weight.repo.DeviceStatusRepository;
import com.example.starter.weight.repo.LateOffsiteEventRepository;
import com.example.starter.weight.repo.ManualPendingRepository;
import com.example.starter.weight.repo.OffsiteWeighRepository;
import com.example.starter.weight.repo.ReferenceRepository;
import com.example.starter.weight.repo.WeighRecordRepository;
import com.example.starter.weight.repo.po.CaseRow;
import com.example.starter.weight.repo.po.DecisionRow;
import com.example.starter.weight.repo.po.DeviceStatusRow;
import com.example.starter.weight.repo.po.ManualPendingRow;
import com.example.starter.weight.repo.po.OffsiteWeighRow;
import com.example.starter.weight.repo.po.VehicleRow;
import com.example.starter.weight.repo.po.WeighRecordRow;

/**
 * 非现场取证接入服务：按车道与过磅时刻接入动态称重数据，
 * 与现场记录按车牌+时刻窗口去重（窗口内只保留首次）；
 * 证据链（称重数据+合成影像标识+含校准有效期的设备状态记录）完整时
 * 自动生成处罚决定（沿用既有处罚与复检语义），缺任一项转人工待补；
 * 结案后迟到数据只记迟到事件，不改已结案件。写操作按 requestId 幂等。
 */
@Service
public class OffsiteEnforcementService {

    private final OffsiteWeighRepository offsiteWeighRepository;
    private final DeviceStatusRepository deviceStatusRepository;
    private final ManualPendingRepository manualPendingRepository;
    private final LateOffsiteEventRepository lateOffsiteEventRepository;
    private final WeighRecordRepository weighRecordRepository;
    private final CaseRepository caseRepository;
    private final DecisionRepository decisionRepository;
    private final ReferenceRepository referenceRepository;
    private final EnforcementService enforcementService;
    private final Clock clock;

    public OffsiteEnforcementService(OffsiteWeighRepository offsiteWeighRepository,
                                     DeviceStatusRepository deviceStatusRepository,
                                     ManualPendingRepository manualPendingRepository,
                                     LateOffsiteEventRepository lateOffsiteEventRepository,
                                     WeighRecordRepository weighRecordRepository,
                                     CaseRepository caseRepository,
                                     DecisionRepository decisionRepository,
                                     ReferenceRepository referenceRepository,
                                     EnforcementService enforcementService,
                                     Clock clock) {
        this.offsiteWeighRepository = offsiteWeighRepository;
        this.deviceStatusRepository = deviceStatusRepository;
        this.manualPendingRepository = manualPendingRepository;
        this.lateOffsiteEventRepository = lateOffsiteEventRepository;
        this.weighRecordRepository = weighRecordRepository;
        this.caseRepository = caseRepository;
        this.decisionRepository = decisionRepository;
        this.referenceRepository = referenceRepository;
        this.enforcementService = enforcementService;
        this.clock = clock;
    }

    /**
     * 非现场动态称重数据接入（幂等）。
     */
    @Transactional
    public OffsiteWeighResponse ingest(OffsiteWeighRequest req) {
        Instant now = Instant.now(clock);
        String imageRef = req.imageRef() == null ? "" : req.imageRef().trim();
        String fingerprint = Fingerprints.of(
                "OFFSITE", req.requestId(), req.plateNo(), req.laneNo(), req.weighTime().toString(),
                req.totalKg().stripTrailingZeros().toPlainString(), imageRef, req.deviceId());

        // 车辆行锁串行化同车牌接入，保证幂等判定与时刻窗口去重的并发正确性
        VehicleRow vehicle = requireVehicle(req.plateNo());

        OffsiteWeighRow existing = offsiteWeighRepository.findByRequestId(req.requestId());
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENT_PARAM_CONFLICT",
                        "requestId 已存在但业务参数不一致，拒绝产生新记录");
            }
            return toResponse(existing, true, false);
        }

        BigDecimal limitKg = referenceRepository.findLimitKg(vehicle.axleType());
        if (limitKg == null) {
            throw ApiException.unprocessable("AXLE_LIMIT_MISSING",
                    "轴型 " + vehicle.axleType() + " 未配置限值");
        }

        Instant windowFrom = req.weighTime().minus(OffsiteRules.DEDUP_WINDOW);
        Instant windowTo = req.weighTime().plus(OffsiteRules.DEDUP_WINDOW);

        // 结案后迟到的非现场数据：只记迟到事件，不改已结案件
        CaseRow closedCase = caseRepository.findClosedByPlateWindow(req.plateNo(), windowFrom, windowTo);
        if (closedCase != null) {
            OffsiteWeighRow lateRow = new OffsiteWeighRow(
                    null, req.requestId(), fingerprint, req.plateNo(), req.laneNo(), req.weighTime(),
                    req.totalKg(), imageRef.isEmpty() ? null : imageRef, req.deviceId(),
                    OffsiteStatus.LATE_EVENT.name(), closedCase.id(), null, now);
            long lateId = insertWithRaceHandling(lateRow, req.requestId(), fingerprint);
            if (lateId < 0) {
                // 并发下同 requestId 已被其他事务写入，返回首次结果
                return toResponse(offsiteWeighRepository.findByRequestId(req.requestId()), true, false);
            }
            lateOffsiteEventRepository.insert(lateId, closedCase.id(), req.plateNo(), req.laneNo(),
                    req.weighTime(),
                    "案件 " + closedCase.caseNo() + " 已结案，迟到非现场数据只记迟到事件，不改已结案件", now);
            return toResponse(offsiteWeighRepository.findById(lateId), false, false);
        }

        // 去重：与现场记录及已接入非现场记录按车牌+时刻窗口比对，窗口内重复只保留首次
        WeighRecordRow onsiteDup = weighRecordRepository.findFirstByPlateWindow(
                req.plateNo(), windowFrom, windowTo);
        if (onsiteDup != null) {
            String caseNo = onsiteDup.caseId() == null ? null : caseNoOf(onsiteDup.caseId());
            return new OffsiteWeighResponse(null, "DUPLICATE", null, caseNo, null, false, false, true);
        }
        OffsiteWeighRow offsiteDup = offsiteWeighRepository.findFirstByPlateWindow(
                req.plateNo(), windowFrom, windowTo);
        if (offsiteDup != null) {
            return toResponse(offsiteDup, false, true);
        }

        // 证据链校验：称重数据（本记录）+ 合成影像标识 + 设备状态记录（校准有效期内）
        PendingReason pendingReason = evaluateEvidence(req, imageRef);
        if (pendingReason != null) {
            OffsiteWeighRow pendingRow = new OffsiteWeighRow(
                    null, req.requestId(), fingerprint, req.plateNo(), req.laneNo(), req.weighTime(),
                    req.totalKg(), imageRef.isEmpty() ? null : imageRef, req.deviceId(),
                    OffsiteStatus.MANUAL_PENDING.name(), null, null, now);
            long pendingId = insertWithRaceHandling(pendingRow, req.requestId(), fingerprint);
            if (pendingId < 0) {
                return toResponse(offsiteWeighRepository.findByRequestId(req.requestId()), true, false);
            }
            manualPendingRepository.insert(pendingId, req.plateNo(), req.laneNo(), req.weighTime(),
                    pendingReason.name(), now);
            return toResponse(offsiteWeighRepository.findById(pendingId), false, false);
        }

        // 证据链完整：立案并自动生成处罚决定（沿用既有处罚与复检语义，可经既有复检接口推翻）
        BigDecimal rate = OverloadRules.overloadRate(req.totalKg(), limitKg);
        DecisionTier tier = OverloadRules.tierOf(rate);
        CaseRow caseRow = enforcementService.createCase(
                req.plateNo(), OffsiteRules.OFFSITE_STATION, req.weighTime(), now);
        String autoRequestId = "AUTO-" + req.requestId();
        DecisionRow decision;
        try {
            decision = enforcementService.createDecisionDoc(
                    caseRow.id(), req.plateNo(), tier, req.totalKg(), limitKg, rate, autoRequestId,
                    Fingerprints.of("DECISION", autoRequestId, req.plateNo(),
                            OffsiteRules.OFFSITE_STATION, req.weighTime().toString()),
                    now);
        } catch (DuplicateKeyException e) {
            // 并发下自动文书已被其他事务写入，重查返回首次结果
            OffsiteWeighRow raced = offsiteWeighRepository.findByRequestId(req.requestId());
            if (raced == null) {
                throw e;
            }
            if (!raced.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENT_PARAM_CONFLICT",
                        "requestId 已存在但业务参数不一致，拒绝产生新记录");
            }
            return toResponse(raced, true, false);
        }
        OffsiteWeighRow decidedRow = new OffsiteWeighRow(
                null, req.requestId(), fingerprint, req.plateNo(), req.laneNo(), req.weighTime(),
                req.totalKg(), imageRef, req.deviceId(),
                OffsiteStatus.AUTO_DECIDED.name(), caseRow.id(), decision.id(), now);
        long decidedId = insertWithRaceHandling(decidedRow, req.requestId(), fingerprint);
        if (decidedId < 0) {
            return toResponse(offsiteWeighRepository.findByRequestId(req.requestId()), true, false);
        }
        return toResponse(offsiteWeighRepository.findById(decidedId), false, false);
    }

    // ---- 内部方法 ----

    /**
     * 证据链缺项评估：缺合成影像标识、缺设备状态记录或校准有效期外，返回对应待补原因；完整返回 null。
     */
    private PendingReason evaluateEvidence(OffsiteWeighRequest req, String imageRef) {
        if (imageRef.isEmpty()) {
            return PendingReason.MISSING_IMAGE;
        }
        DeviceStatusRow device = deviceStatusRepository.findByDeviceId(req.deviceId());
        if (device == null) {
            return PendingReason.DEVICE_STATUS_MISSING;
        }
        if (!OffsiteRules.withinCalibration(req.weighTime(), device.calibratedFrom(), device.calibratedUntil())) {
            return PendingReason.DEVICE_CAL_EXPIRED;
        }
        return null;
    }

    /**
     * 插入非现场记录；并发下同 requestId 已被写入时返回 -1（由调用方重查返回首次结果），
     * 同键异参时抛出 409。
     */
    private long insertWithRaceHandling(OffsiteWeighRow row, String requestId, String fingerprint) {
        try {
            return offsiteWeighRepository.insert(row);
        } catch (DuplicateKeyException e) {
            OffsiteWeighRow raced = offsiteWeighRepository.findByRequestId(requestId);
            if (raced == null) {
                throw e;
            }
            if (!raced.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENT_PARAM_CONFLICT",
                        "requestId 已存在但业务参数不一致，拒绝产生新记录");
            }
            return -1L;
        }
    }

    private VehicleRow requireVehicle(String plateNo) {
        VehicleRow vehicle = referenceRepository.findVehicleForUpdate(plateNo);
        if (vehicle == null) {
            throw ApiException.notFound("车辆档案不存在: " + plateNo);
        }
        return vehicle;
    }

    private String caseNoOf(long caseId) {
        CaseRow caseRow = caseRepository.findById(caseId);
        return caseRow == null ? null : caseRow.caseNo();
    }

    private OffsiteWeighResponse toResponse(OffsiteWeighRow row, boolean replayed, boolean deduped) {
        if (row == null) {
            throw ApiException.notFound("非现场称重记录不存在");
        }
        String caseNo = row.caseId() == null ? null : caseNoOf(row.caseId());
        String pendingReason = null;
        if (OffsiteStatus.MANUAL_PENDING.name().equals(row.status())) {
            ManualPendingRow pending = manualPendingRepository.findByOffsiteWeighId(row.id());
            pendingReason = pending == null ? null : pending.reason();
        }
        DecisionResponse decision = null;
        if (row.decisionId() != null) {
            DecisionRow doc = decisionRepository.findById(row.decisionId());
            if (doc != null) {
                decision = new DecisionResponse(doc.id(), doc.docNo(), caseNo, doc.docType(), doc.tier(),
                        doc.totalKg(), doc.limitKg(), doc.overloadRate(), doc.fineAmount(), doc.points(),
                        doc.status(), doc.servedAt(), false);
            }
        }
        return new OffsiteWeighResponse(row.id(), row.status(), pendingReason, caseNo, decision,
                OffsiteStatus.LATE_EVENT.name().equals(row.status()), replayed, deduped);
    }
}
