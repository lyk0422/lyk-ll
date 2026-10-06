package com.example.starter.weight.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.weight.api.ApiException;
import com.example.starter.weight.api.dto.OffsiteWeighRequest;
import com.example.starter.weight.api.dto.OffsiteWeighResponse;
import com.example.starter.weight.domain.CaseStatus;
import com.example.starter.weight.domain.DecisionTier;
import com.example.starter.weight.domain.OverloadRules;
import com.example.starter.weight.domain.WeighMode;
import com.example.starter.weight.repo.CaseRepository;
import com.example.starter.weight.repo.DecisionRepository;
import com.example.starter.weight.repo.OffsiteLateEventRepository;
import com.example.starter.weight.repo.OffsiteWeighRecordRepository;
import com.example.starter.weight.repo.ReferenceRepository;
import com.example.starter.weight.repo.WeighDeviceRepository;
import com.example.starter.weight.repo.WeighRecordRepository;
import com.example.starter.weight.repo.po.CaseRow;
import com.example.starter.weight.repo.po.DecisionRow;
import com.example.starter.weight.repo.po.OffsiteWeighRecordRow;
import com.example.starter.weight.repo.po.VehicleRow;
import com.example.starter.weight.repo.po.WeighDeviceRow;
import com.example.starter.weight.repo.po.WeighRecordRow;

/**
 * 非现场执法接入服务：动态称重数据按车道与过磅时刻接入，
 * 与现场记录按车牌与时刻窗口（±5 分钟）去重只保留首次；
 * 证据链须含称重数据、合成影像标识、设备状态记录（含校准有效期）三项，
 * 缺任一项或校准有效期外不得自动生成处罚决定，转人工待补；
 * 证据链齐全时沿用既有处罚与复检语义自动生成处罚决定；
 * 结案后迟到的非现场数据只记迟到事件，不改已结案件。
 */
@Service
public class OffsiteEnforcementService {

    /**
     * 非现场与现场记录的去重时刻窗口：±5 分钟，窗口内重复数据只保留首次。
     */
    static final Duration DEDUP_WINDOW = Duration.ofMinutes(5);

    /**
     * 证据状态：已入证据链并自动生成处罚决定。
     */
    static final String STATUS_COMPLETE = "COMPLETE";
    /**
     * 证据状态：证据链缺项（含校准有效期外），转人工待补。
     */
    static final String STATUS_MANUAL_PENDING = "MANUAL_PENDING";
    /**
     * 证据状态：窗口内重复数据，只保留首次。
     */
    static final String STATUS_DUPLICATE = "DUPLICATE";
    /**
     * 证据状态：结案后迟到，只记迟到事件。
     */
    static final String STATUS_LATE = "LATE";

    /**
     * 证据链缺项：合成影像标识缺失。
     */
    static final String MISSING_IMAGE_REF = "IMAGE_REF";
    /**
     * 证据链缺项：设备状态记录缺失或设备非正常。
     */
    static final String MISSING_DEVICE_STATUS = "DEVICE_STATUS";
    /**
     * 证据链缺项：过磅时刻在校准有效期外。
     */
    static final String MISSING_CALIBRATION_EXPIRED = "CALIBRATION_EXPIRED";

    private final ReferenceRepository referenceRepository;
    private final WeighDeviceRepository weighDeviceRepository;
    private final OffsiteWeighRecordRepository offsiteRepository;
    private final OffsiteLateEventRepository lateEventRepository;
    private final WeighRecordRepository weighRecordRepository;
    private final CaseRepository caseRepository;
    private final DecisionRepository decisionRepository;
    private final EnforcementService enforcementService;
    private final Clock clock;

    public OffsiteEnforcementService(ReferenceRepository referenceRepository,
                                     WeighDeviceRepository weighDeviceRepository,
                                     OffsiteWeighRecordRepository offsiteRepository,
                                     OffsiteLateEventRepository lateEventRepository,
                                     WeighRecordRepository weighRecordRepository,
                                     CaseRepository caseRepository,
                                     DecisionRepository decisionRepository,
                                     EnforcementService enforcementService,
                                     Clock clock) {
        this.referenceRepository = referenceRepository;
        this.weighDeviceRepository = weighDeviceRepository;
        this.offsiteRepository = offsiteRepository;
        this.lateEventRepository = lateEventRepository;
        this.weighRecordRepository = weighRecordRepository;
        this.caseRepository = caseRepository;
        this.decisionRepository = decisionRepository;
        this.enforcementService = enforcementService;
        this.clock = clock;
    }

    /**
     * 非现场动态称重接入：幂等、窗口去重、证据链校验、自动处罚、迟到事件。
     */
    @Transactional
    public OffsiteWeighResponse ingest(OffsiteWeighRequest req) {
        try {
            return doIngest(req);
        } catch (RaceReplayException e) {
            // 并发下同 requestId 已被其他事务抢先写入：重放首次结果，不产生任何副作用
            return toResponse(e.row(), true);
        }
    }

    private OffsiteWeighResponse doIngest(OffsiteWeighRequest req) {
        Instant now = Instant.now(clock);
        String fingerprint = Fingerprints.of(
                "OFFSITE", req.requestId(), req.plateNo(), req.laneCode(), req.deviceCode(),
                req.weighTime().toString(), req.totalKg().stripTrailingZeros().toPlainString(),
                req.imageRef() == null ? "" : req.imageRef());

        OffsiteWeighRecordRow existing = offsiteRepository.findByRequestId(req.requestId());
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENT_PARAM_CONFLICT",
                        "requestId 已存在但业务参数不一致，拒绝产生新记录");
            }
            return toResponse(existing, true);
        }

        VehicleRow vehicle = referenceRepository.findVehicle(req.plateNo());
        if (vehicle == null) {
            throw ApiException.notFound("车辆档案不存在: " + req.plateNo());
        }
        BigDecimal limitKg = referenceRepository.findLimitKg(vehicle.axleType());
        if (limitKg == null) {
            throw ApiException.unprocessable("AXLE_LIMIT_MISSING",
                    "轴型 " + vehicle.axleType() + " 未配置限值");
        }
        BigDecimal rate = OverloadRules.overloadRate(req.totalKg(), limitKg);
        DecisionTier tier = OverloadRules.tierOf(rate);

        WeighDeviceRow device = weighDeviceRepository.findByDeviceCode(req.deviceCode());
        Instant windowFrom = req.weighTime().minus(DEDUP_WINDOW);
        Instant windowTo = req.weighTime().plus(DEDUP_WINDOW);

        // 结案后迟到：窗口内同车牌同检测站案件已结案，只记迟到事件，不改已结案件
        if (device != null) {
            CaseRow closedCase = caseRepository.findByPlateStationWithinWindow(
                    req.plateNo(), device.stationCode(), windowFrom, windowTo).stream()
                    .filter(c -> CaseStatus.CLOSED.name().equals(c.status()))
                    .findFirst()
                    .orElse(null);
            if (closedCase != null) {
                long id = insertGate(req, fingerprint, vehicle, limitKg, rate, tier,
                        STATUS_LATE, "", null, null, now);
                lateEventRepository.insert(id, closedCase.id(), req.plateNo(), req.laneCode(),
                        req.weighTime(), req.totalKg(),
                        "案件 " + closedCase.caseNo() + " 已结案，迟到非现场数据只记迟到事件，不改已结案件", now);
                offsiteRepository.updateCaseAndDecision(id, closedCase.id(), null);
                return toResponse(offsiteRepository.findById(id), false);
            }
        }

        // 证据链三项：称重数据（入参已校验）、合成影像标识、设备状态记录（含校准有效期）
        List<String> missing = new ArrayList<>();
        if (req.imageRef() == null || req.imageRef().isBlank()) {
            missing.add(MISSING_IMAGE_REF);
        }
        if (device == null || !"NORMAL".equals(device.status())) {
            missing.add(MISSING_DEVICE_STATUS);
        } else if (req.weighTime().isBefore(device.calibrationValidFrom())
                || req.weighTime().isAfter(device.calibrationValidUntil())) {
            missing.add(MISSING_CALIBRATION_EXPIRED);
        }
        if (!missing.isEmpty()) {
            // 缺任一项不得自动生成处罚决定，转人工待补
            long id = insertGate(req, fingerprint, vehicle, limitKg, rate, tier,
                    STATUS_MANUAL_PENDING, String.join(",", missing), null, null, now);
            return toResponse(offsiteRepository.findById(id), false);
        }

        // 窗口去重：先现场记录，再已保留的非现场记录；窗口内重复数据只保留首次
        WeighRecordRow onsite = weighRecordRepository.findByPlateWithinWindow(
                req.plateNo(), windowFrom, windowTo);
        if (onsite != null) {
            long id = insertGate(req, fingerprint, vehicle, limitKg, rate, tier,
                    STATUS_DUPLICATE, "", "ONSITE", onsite.id(), now);
            return toResponse(offsiteRepository.findById(id), false);
        }
        OffsiteWeighRecordRow anchor = offsiteRepository.findRetainedByPlateWithinWindow(
                req.plateNo(), windowFrom, windowTo);
        if (anchor != null) {
            long id = insertGate(req, fingerprint, vehicle, limitKg, rate, tier,
                    STATUS_DUPLICATE, "", "OFFSITE", anchor.id(), now);
            return toResponse(offsiteRepository.findById(id), false);
        }

        // 证据链齐全且非重复：先落接入记录作为幂等闸门，再立案、入过磅记录、自动生成处罚决定
        long id = insertGate(req, fingerprint, vehicle, limitKg, rate, tier,
                STATUS_COMPLETE, "", null, null, now);

        CaseRow caseRow = caseRepository.findByBizKey(req.plateNo(), device.stationCode(), req.weighTime());
        if (caseRow == null) {
            caseRow = enforcementService.createCase(req.plateNo(), device.stationCode(),
                    req.weighTime(), now);
        }
        String role = enforcementService.resolveLiveRole(
                req.plateNo(), device.stationCode(), req.weighTime(), WeighMode.DYNAMIC);
        WeighRecordRow weighRow = new WeighRecordRow(
                null, "OSW-" + req.requestId(), Fingerprints.of("OFFSITE_WEIGH", req.requestId()),
                req.plateNo(), device.stationCode(), req.weighTime(), WeighMode.DYNAMIC.name(),
                req.totalKg(), vehicle.axleType(), limitKg, rate, tier.name(), role, caseRow.id(), now);
        try {
            weighRecordRepository.insert(weighRow);
        } catch (DuplicateKeyException e) {
            // 并发下同业务键现场/非现场记录已抢先写入：本数据转为窗口去重，只保留首次
            WeighRecordRow raced = weighRecordRepository.findByDedupKey(
                    req.plateNo(), device.stationCode(), req.weighTime(), WeighMode.DYNAMIC.name());
            offsiteRepository.markDuplicate(id, "ONSITE", raced == null ? null : raced.id());
            return toResponse(offsiteRepository.findById(id), false);
        }

        WeighRecordRow authoritative = weighRecordRepository.findAuthoritative(
                req.plateNo(), device.stationCode(), req.weighTime());
        Long decisionId = null;
        if (authoritative != null) {
            // 沿用既有处罚语义自动生成处罚决定（复检推翻时撤销与退费仍在同一事务）
            String autoRequestId = "AUTO-" + req.requestId();
            decisionId = enforcementService.createDecisionForCase(caseRow, authoritative, req.plateNo(),
                    autoRequestId, Fingerprints.of("DECISION", autoRequestId, req.plateNo(),
                            device.stationCode(), req.weighTime().toString()), now);
        }
        offsiteRepository.updateCaseAndDecision(id, caseRow.id(), decisionId);
        return toResponse(offsiteRepository.findById(id), false);
    }

    /**
     * 插入接入记录作为幂等闸门：唯一键冲突时重查，异参 409，同参竞态抛出重放信号。
     */
    private long insertGate(OffsiteWeighRequest req, String fingerprint, VehicleRow vehicle,
                            BigDecimal limitKg, BigDecimal rate, DecisionTier tier,
                            String evidenceStatus, String missingItems,
                            String dedupRefType, Long dedupRefId, Instant now) {
        OffsiteWeighRecordRow row = new OffsiteWeighRecordRow(
                null, req.requestId(), fingerprint, req.plateNo(), req.laneCode(), req.deviceCode(),
                req.weighTime(), req.totalKg(), vehicle.axleType(), limitKg, rate, tier.name(),
                req.imageRef(), evidenceStatus, missingItems, dedupRefType, dedupRefId,
                null, null, now);
        try {
            return offsiteRepository.insert(row);
        } catch (DuplicateKeyException e) {
            OffsiteWeighRecordRow raced = offsiteRepository.findByRequestId(req.requestId());
            if (raced == null) {
                throw e;
            }
            if (!raced.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENT_PARAM_CONFLICT",
                        "requestId 已存在但业务参数不一致，拒绝产生新记录");
            }
            throw new RaceReplayException(raced);
        }
    }

    /**
     * 并发竞态重放信号：同 requestId 同参数的记录已被其他事务写入。
     */
    private static final class RaceReplayException extends RuntimeException {
        private final OffsiteWeighRecordRow row;

        private RaceReplayException(OffsiteWeighRecordRow row) {
            super(null, null, false, false);
            this.row = row;
        }

        private OffsiteWeighRecordRow row() {
            return row;
        }
    }

    private OffsiteWeighResponse toResponse(OffsiteWeighRecordRow row, boolean replayed) {
        if (row == null) {
            throw ApiException.notFound("非现场接入记录不存在");
        }
        String caseNo = null;
        if (row.caseId() != null) {
            CaseRow caseRow = caseRepository.findById(row.caseId());
            caseNo = caseRow == null ? null : caseRow.caseNo();
        }
        String decisionDocNo = null;
        if (row.decisionId() != null) {
            DecisionRow decision = decisionRepository.findById(row.decisionId());
            decisionDocNo = decision == null ? null : decision.docNo();
        }
        List<String> missing = row.missingItems() == null || row.missingItems().isEmpty()
                ? List.of()
                : Arrays.asList(row.missingItems().split(","));
        return new OffsiteWeighResponse(row.id(), row.plateNo(), row.laneCode(), row.deviceCode(),
                row.weighTime(), row.totalKg(), row.overloadRate(), row.tier(), row.evidenceStatus(),
                missing, caseNo, decisionDocNo,
                STATUS_LATE.equals(row.evidenceStatus()), replayed,
                STATUS_DUPLICATE.equals(row.evidenceStatus()));
    }
}
