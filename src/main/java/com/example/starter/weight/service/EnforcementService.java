package com.example.starter.weight.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.weight.api.ApiException;
import com.example.starter.weight.api.dto.CaseResponse;
import com.example.starter.weight.api.dto.DecisionRequest;
import com.example.starter.weight.api.dto.DecisionResponse;
import com.example.starter.weight.api.dto.ReviewApplyRequest;
import com.example.starter.weight.api.dto.ReviewConcludeRequest;
import com.example.starter.weight.api.dto.ReviewResponse;
import com.example.starter.weight.api.dto.WeighRequest;
import com.example.starter.weight.api.dto.WeighResponse;
import com.example.starter.weight.domain.CaseStatus;
import com.example.starter.weight.domain.DecisionStatus;
import com.example.starter.weight.domain.DecisionTier;
import com.example.starter.weight.domain.DocType;
import com.example.starter.weight.domain.OverloadRules;
import com.example.starter.weight.domain.RecordRole;
import com.example.starter.weight.domain.ReviewStatus;
import com.example.starter.weight.domain.WeighMode;
import com.example.starter.weight.repo.CaseRepository;
import com.example.starter.weight.repo.DecisionRepository;
import com.example.starter.weight.repo.LateWeighEventRepository;
import com.example.starter.weight.repo.LedgerRepository;
import com.example.starter.weight.repo.ReferenceRepository;
import com.example.starter.weight.repo.ReviewRepository;
import com.example.starter.weight.repo.SequenceRepository;
import com.example.starter.weight.repo.WeighRecordRepository;
import com.example.starter.weight.repo.po.CaseRow;
import com.example.starter.weight.repo.po.DecisionRow;
import com.example.starter.weight.repo.po.ReviewRow;
import com.example.starter.weight.repo.po.StationRow;
import com.example.starter.weight.repo.po.VehicleRow;
import com.example.starter.weight.repo.po.WeighRecordRow;

/**
 * 治超执法核心服务：过磅判定、静态复检优先、处罚决定幂等、
 * 复检 7 日期限与唯一受理、撤销原子性、结案终态。
 */
@Service
public class EnforcementService {

    private static final String REASON_PENALTY = "PENALTY";
    private static final String REASON_REFUND = "REVOCATION_REFUND";

    private final ReferenceRepository referenceRepository;
    private final WeighRecordRepository weighRecordRepository;
    private final CaseRepository caseRepository;
    private final DecisionRepository decisionRepository;
    private final ReviewRepository reviewRepository;
    private final LedgerRepository ledgerRepository;
    private final LateWeighEventRepository lateWeighEventRepository;
    private final SequenceRepository sequenceRepository;
    private final Clock clock;

    public EnforcementService(ReferenceRepository referenceRepository,
                              WeighRecordRepository weighRecordRepository,
                              CaseRepository caseRepository,
                              DecisionRepository decisionRepository,
                              ReviewRepository reviewRepository,
                              LedgerRepository ledgerRepository,
                              LateWeighEventRepository lateWeighEventRepository,
                              SequenceRepository sequenceRepository,
                              Clock clock) {
        this.referenceRepository = referenceRepository;
        this.weighRecordRepository = weighRecordRepository;
        this.caseRepository = caseRepository;
        this.decisionRepository = decisionRepository;
        this.reviewRepository = reviewRepository;
        this.ledgerRepository = ledgerRepository;
        this.lateWeighEventRepository = lateWeighEventRepository;
        this.sequenceRepository = sequenceRepository;
        this.clock = clock;
    }

    /**
     * 车辆过磅上报：查限值、计算超限率与分档，按业务键去重、静态复检优先、结案后迟到备查。
     */
    @Transactional
    public WeighResponse submitWeigh(WeighRequest req) {
        Instant now = Instant.now(clock);
        String fingerprint = Fingerprints.of(
                req.requestId(), req.plateNo(), req.stationCode(), req.weighTime().toString(),
                req.mode().name(), req.totalKg().stripTrailingZeros().toPlainString());

        WeighRecordRow existing = weighRecordRepository.findByRequestId(req.requestId());
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENT_PARAM_CONFLICT",
                        "requestId 已存在但业务参数不一致，拒绝产生新记录");
            }
            return toWeighResponse(existing, true, false);
        }

        VehicleRow vehicle = requireVehicle(req.plateNo());
        requireStation(req.stationCode());
        BigDecimal limitKg = referenceRepository.findLimitKg(vehicle.axleType());
        if (limitKg == null) {
            throw ApiException.unprocessable("AXLE_LIMIT_MISSING",
                    "轴型 " + vehicle.axleType() + " 未配置限值");
        }
        BigDecimal rate = OverloadRules.overloadRate(req.totalKg(), limitKg);
        DecisionTier tier = OverloadRules.tierOf(rate);

        // 业务去重键：车牌+检测站+过磅时刻+方式
        WeighRecordRow sameKey = weighRecordRepository.findByDedupKey(
                req.plateNo(), req.stationCode(), req.weighTime(), req.mode().name());
        if (sameKey != null) {
            return toWeighResponse(sameKey, false, true);
        }

        CaseRow caseRow = caseRepository.findByBizKey(
                req.plateNo(), req.stationCode(), req.weighTime());
        boolean closed = caseRow != null && CaseStatus.CLOSED.name().equals(caseRow.status());

        String role;
        Long caseId;
        if (closed) {
            // 结案后迟到过磅数据：只记迟到事件并入证据备查，不改判已结案件
            role = RecordRole.LATE_EVIDENCE.name();
            caseId = caseRow.id();
        } else {
            if (caseRow == null) {
                caseRow = createCase(req.plateNo(), req.stationCode(), req.weighTime(), now);
            }
            caseId = caseRow.id();
            role = resolveLiveRole(req.plateNo(), req.stationCode(), req.weighTime(), req.mode());
        }

        WeighRecordRow row = new WeighRecordRow(
                null, req.requestId(), fingerprint, req.plateNo(), req.stationCode(), req.weighTime(),
                req.mode().name(), req.totalKg(), vehicle.axleType(), limitKg, rate, tier.name(),
                role, caseId, now);
        long id;
        try {
            id = weighRecordRepository.insert(row);
        } catch (DuplicateKeyException e) {
            // 并发下幂等键或去重键已被其他事务写入，重查返回首次结果
            WeighRecordRow raced = weighRecordRepository.findByRequestId(req.requestId());
            if (raced != null) {
                if (!raced.fingerprint().equals(fingerprint)) {
                    throw ApiException.conflict("IDEMPOTENT_PARAM_CONFLICT",
                            "requestId 已存在但业务参数不一致，拒绝产生新记录");
                }
                return toWeighResponse(raced, true, false);
            }
            WeighRecordRow dedupRaced = weighRecordRepository.findByDedupKey(
                    req.plateNo(), req.stationCode(), req.weighTime(), req.mode().name());
            return toWeighResponse(dedupRaced, false, true);
        }

        if (closed) {
            lateWeighEventRepository.insert(id, caseRow.id(), req.plateNo(), req.stationCode(),
                    req.weighTime(), req.mode().name(), req.totalKg(),
                    "案件 " + caseRow.caseNo() + " 已结案，迟到过磅数据仅入证据备查，不改判", now);
        } else if (req.mode() == WeighMode.STATIC) {
            // 静态复检优先：动态记录降级为被取代并保留可查
            weighRecordRepository.findAllByBizKey(req.plateNo(), req.stationCode(), req.weighTime())
                    .stream()
                    .filter(r -> r.mode().equals(WeighMode.DYNAMIC.name())
                            && RecordRole.AUTHORITATIVE.name().equals(r.role())
                            && r.id() != id)
                    .forEach(r -> weighRecordRepository.markSuperseded(r.id()));
        }

        return toWeighResponse(weighRecordRepository.findById(id), false, false);
    }

    /**
     * 依据在案权威过磅记录生成处罚决定（幂等）。
     */
    @Transactional
    public DecisionResponse issueDecision(DecisionRequest req) {
        Instant now = Instant.now(clock);
        String fingerprint = Fingerprints.of(
                "DECISION", req.requestId(), req.plateNo(), req.stationCode(), req.weighTime().toString());

        DecisionRow existing = decisionRepository.findByRequestId(req.requestId());
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENT_PARAM_CONFLICT",
                        "requestId 已存在但业务参数不一致，拒绝产生新文书");
            }
            return toDecisionResponse(existing, true);
        }

        CaseRow caseRow = requireOpenCase(req.plateNo(), req.stationCode(), req.weighTime());
        WeighRecordRow authoritative = weighRecordRepository.findAuthoritative(
                req.plateNo(), req.stationCode(), req.weighTime());
        if (authoritative == null) {
            throw ApiException.notFound("案件缺少可作为判定依据的过磅记录");
        }

        DecisionTier tier = DecisionTier.valueOf(authoritative.tier());
        DocType docType = switch (tier) {
            case FINE_POINTS -> DocType.PENALTY;
            case UNLOAD_RELEASE -> DocType.UNLOAD_RELEASE;
            case NO_OVERLOAD -> DocType.NO_ACTION;
        };
        BigDecimal fineAmount = BigDecimal.ZERO.setScale(2);
        int points = 0;
        if (tier == DecisionTier.FINE_POINTS) {
            fineAmount = OverloadRules.FINE_AMOUNT;
            points = OverloadRules.FINE_POINTS;
        }

        String docNo = sequenceRepository.nextDocNo();
        DecisionRow row = new DecisionRow(
                null, req.requestId(), fingerprint, docNo, caseRow.id(), docType.name(), tier.name(),
                authoritative.totalKg(), authoritative.limitKg(), authoritative.overloadRate(),
                fineAmount, points, DecisionStatus.ACTIVE.name(), now, null, now);
        long id;
        try {
            id = decisionRepository.insert(row);
        } catch (DuplicateKeyException e) {
            DecisionRow raced = decisionRepository.findByRequestId(req.requestId());
            if (raced == null) {
                throw e;
            }
            if (!raced.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENT_PARAM_CONFLICT",
                        "requestId 已存在但业务参数不一致，拒绝产生新文书");
            }
            return toDecisionResponse(raced, true);
        }

        if (points > 0) {
            ledgerRepository.appendPoints(req.plateNo(), caseRow.id(), id, points, REASON_PENALTY, now);
        }
        if (fineAmount.signum() > 0) {
            ledgerRepository.appendFine(req.plateNo(), caseRow.id(), id, fineAmount, REASON_PENALTY, now);
        }
        return toDecisionResponse(decisionRepository.findByRequestId(req.requestId()), false);
    }

    /**
     * 复检申请：处罚送达后 7 个自然日（UTC 日界）内一次，恰第 7 日受理，逾期拒绝；同一案件唯一受理。
     */
    @Transactional
    public ReviewResponse applyReview(ReviewApplyRequest req) {
        Instant now = Instant.now(clock);
        String fingerprint = Fingerprints.of(
                "REVIEW_APPLY", req.requestId(), req.plateNo(), req.stationCode(),
                req.weighTime().toString(), req.reason());

        ReviewRow existing = reviewRepository.findByRequestId(req.requestId());
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENT_PARAM_CONFLICT",
                        "requestId 已存在但业务参数不一致，拒绝重复申请");
            }
            return toReviewResponse(existing, true);
        }

        CaseRow caseRow = requireCase(req.plateNo(), req.stationCode(), req.weighTime());
        DecisionRow decision = decisionRepository.findActiveByCaseId(caseRow.id());
        if (decision == null) {
            throw ApiException.unprocessable("NO_ACTIVE_PENALTY", "案件不存在生效处罚决定，不能申请复检");
        }
        if (decision.points() <= 0 && decision.fineAmount().signum() == 0) {
            throw ApiException.unprocessable("NO_PENALTY_REVIEWABLE", "无罚款记分的处理决定不适用复检申诉");
        }
        if (!withinSevenUtcDays(decision.servedAt(), now)) {
            throw ApiException.unprocessable("REVIEW_WINDOW_EXPIRED",
                    "复检申请已超过送达后 7 个自然日（UTC 日界），逾期拒绝");
        }

        ReviewRow row = new ReviewRow(null, req.requestId(), fingerprint, caseRow.id(), decision.id(),
                req.reason(), ReviewStatus.UNDER_REVIEW.name(), now, null, null, null, null);
        long id;
        try {
            id = reviewRepository.insert(row);
        } catch (DuplicateKeyException e) {
            // 同一案件并发复检：uk_review_case 保证唯一受理
            ReviewRow raced = reviewRepository.findByCaseId(caseRow.id());
            if (raced != null && raced.requestId().equals(req.requestId())) {
                if (!raced.fingerprint().equals(fingerprint)) {
                    throw ApiException.conflict("IDEMPOTENT_PARAM_CONFLICT",
                            "requestId 已存在但业务参数不一致，拒绝重复申请");
                }
                return toReviewResponse(raced, true);
            }
            throw ApiException.conflict("REVIEW_ALREADY_EXISTS", "同一案件仅受理一次复检申请");
        }
        return toReviewResponse(reviewRepository.findByIdForUpdate(id), false);
    }

    /**
     * 复检结论：推翻时同一事务内撤销原决定、退还记分与罚款并生成撤销文书，保证原子性。
     */
    @Transactional
    public ReviewResponse concludeReview(ReviewConcludeRequest req) {
        Instant now = Instant.now(clock);
        String fingerprint = Fingerprints.of(
                "REVIEW_CONCLUDE", req.requestId(), req.plateNo(), req.stationCode(),
                req.weighTime().toString(), String.valueOf(req.overturn()),
                req.recheckTotalKg() == null ? "" : req.recheckTotalKg().stripTrailingZeros().toPlainString());

        ReviewRow existing = reviewRepository.findByConcludeRequestId(req.requestId());
        if (existing != null) {
            if (!existing.concludeFingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENT_PARAM_CONFLICT",
                        "结论 requestId 已存在但业务参数不一致，拒绝重复结论");
            }
            return toReviewResponse(existing, true);
        }

        CaseRow caseRow = requireCase(req.plateNo(), req.stationCode(), req.weighTime());
        ReviewRow review = reviewRepository.findByCaseId(caseRow.id());
        if (review == null) {
            throw ApiException.notFound("案件未查询到已受理的复检申请");
        }
        review = reviewRepository.findByIdForUpdate(review.id());
        if (!ReviewStatus.UNDER_REVIEW.name().equals(review.status())) {
            throw ApiException.conflict("REVIEW_ALREADY_CONCLUDED", "复检申请已有结论");
        }

        ReviewStatus target = Boolean.TRUE.equals(req.overturn())
                ? ReviewStatus.OVERTURNED : ReviewStatus.UPHELD;

        if (target == ReviewStatus.OVERTURNED) {
            if (req.recheckTotalKg() == null) {
                throw ApiException.unprocessable("RECHECK_WEIGHT_REQUIRED", "推翻原处罚须提供静态复检总重");
            }
            DecisionRow decision = decisionRepository.findByIdForUpdate(review.decisionId());
            if (decision == null || !DecisionStatus.ACTIVE.name().equals(decision.status())) {
                throw ApiException.unprocessable("NO_ACTIVE_PENALTY", "原处罚决定已不生效，不能撤销");
            }
            // 同一事务：撤销原决定 -> 退还记分/罚款 -> 生成撤销文书；任一失败整体回滚
            int revoked = decisionRepository.revoke(decision.id(), now);
            if (revoked != 1) {
                throw ApiException.conflict("PENALTY_REVOKE_RACE", "原处罚决定状态已变化，撤销失败");
            }
            if (decision.points() > 0) {
                ledgerRepository.appendPoints(req.plateNo(), caseRow.id(), decision.id(),
                        -decision.points(), REASON_REFUND, now);
            }
            if (decision.fineAmount().signum() > 0) {
                ledgerRepository.appendFine(req.plateNo(), caseRow.id(), decision.id(),
                        decision.fineAmount().negate(), REASON_REFUND, now);
            }
            String revocationNo = sequenceRepository.nextDocNo();
            DecisionRow revocation = new DecisionRow(
                    null, "REV-" + req.requestId(),
                    Fingerprints.of("REVOCATION", req.requestId(), String.valueOf(decision.id())),
                    revocationNo, caseRow.id(), DocType.REVOCATION.name(), decision.tier(),
                    req.recheckTotalKg(), decision.limitKg(),
                    OverloadRules.overloadRate(req.recheckTotalKg(), decision.limitKg()),
                    BigDecimal.ZERO.setScale(2), 0, DecisionStatus.ACTIVE.name(), now, null, now);
            decisionRepository.insert(revocation);
        }

        int updated = reviewRepository.conclude(review.id(), target.name(), req.requestId(), fingerprint,
                now, req.recheckTotalKg());
        if (updated != 1) {
            throw ApiException.conflict("REVIEW_ALREADY_CONCLUDED", "复检申请已有结论");
        }
        return toReviewResponse(reviewRepository.findByCaseId(caseRow.id()), false);
    }

    /**
     * 案件结案（终态）：重复结案返回幂等成功，不产生新文书。
     */
    @Transactional
    public CaseResponse closeCase(com.example.starter.weight.api.dto.CloseCaseRequest req) {
        Instant now = Instant.now(clock);
        String fingerprint = Fingerprints.of(
                "CLOSE", req.requestId(), req.plateNo(), req.stationCode(), req.weighTime().toString());

        CaseRow byCloseReq = caseRepository.findByCloseRequestId(req.requestId());
        if (byCloseReq != null) {
            if (!byCloseReq.closeFingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENT_PARAM_CONFLICT",
                        "结案 requestId 已存在但业务参数不一致，拒绝重复结案");
            }
            return toCaseResponse(byCloseReq, true);
        }

        CaseRow caseRow = requireCase(req.plateNo(), req.stationCode(), req.weighTime());
        int closed = caseRepository.close(caseRow.id(), req.requestId(), fingerprint, now);
        CaseRow latest = caseRepository.findById(caseRow.id());
        // closed == 0 表示此前已结案：终态下重复结案仍返回幂等成功
        return toCaseResponse(latest, closed == 0);
    }

    /**
     * 查询案件与文书链（含撤销文书）。
     */
    @Transactional(readOnly = true)
    public CaseResponse getCase(String caseNo) {
        CaseRow caseRow = caseRepository.findByCaseNo(caseNo);
        if (caseRow == null) {
            throw ApiException.notFound("案件不存在: " + caseNo);
        }
        return toCaseResponse(caseRow, false);
    }

    // ---- 内部方法 ----

    private CaseRow createCase(String plateNo, String stationCode, Instant weighTime, Instant now) {
        String caseNo = sequenceRepository.nextCaseNo();
        CaseRow row = new CaseRow(null, caseNo, plateNo, stationCode, weighTime,
                CaseStatus.OPEN.name(), null, null, null, now);
        try {
            caseRepository.insert(row);
        } catch (DuplicateKeyException e) {
            CaseRow raced = caseRepository.findByBizKey(plateNo, stationCode, weighTime);
            if (raced != null) {
                return raced;
            }
            throw e;
        }
        return caseRepository.findByCaseNo(caseNo);
    }

    /**
     * 判定非迟到记录角色：静态复检优先；静态已在案时后到动态降级保留。
     */
    private String resolveLiveRole(String plateNo, String stationCode, Instant weighTime, WeighMode mode) {
        if (mode == WeighMode.STATIC) {
            return RecordRole.AUTHORITATIVE.name();
        }
        List<WeighRecordRow> all = weighRecordRepository.findAllByBizKey(plateNo, stationCode, weighTime);
        boolean hasStatic = all.stream().anyMatch(r -> r.mode().equals(WeighMode.STATIC.name()));
        return hasStatic ? RecordRole.SUPERSEDED.name() : RecordRole.AUTHORITATIVE.name();
    }

    private VehicleRow requireVehicle(String plateNo) {
        VehicleRow vehicle = referenceRepository.findVehicle(plateNo);
        if (vehicle == null) {
            throw ApiException.notFound("车辆档案不存在: " + plateNo);
        }
        return vehicle;
    }

    private StationRow requireStation(String stationCode) {
        StationRow station = referenceRepository.findStation(stationCode);
        if (station == null) {
            throw ApiException.notFound("检测站不存在: " + stationCode);
        }
        return station;
    }

    private CaseRow requireCase(String plateNo, String stationCode, Instant weighTime) {
        CaseRow caseRow = caseRepository.findByBizKey(plateNo, stationCode, weighTime);
        if (caseRow == null) {
            throw ApiException.notFound("案件不存在（车牌+检测站+过磅时刻未匹配）");
        }
        return caseRow;
    }

    private CaseRow requireOpenCase(String plateNo, String stationCode, Instant weighTime) {
        CaseRow caseRow = requireCase(plateNo, stationCode, weighTime);
        if (CaseStatus.CLOSED.name().equals(caseRow.status())) {
            throw ApiException.conflict("CASE_CLOSED", "案件已结案，不能再生成新文书");
        }
        return caseRow;
    }

    /**
     * 送达后 7 个自然日（UTC 日界）内：申请日 - 送达日 &lt;= 7，恰第 7 日受理，第 8 日起拒绝。
     */
    private boolean withinSevenUtcDays(Instant servedAt, Instant appliedAt) {
        LocalDate servedDay = servedAt.atOffset(ZoneOffset.UTC).toLocalDate();
        LocalDate appliedDay = appliedAt.atOffset(ZoneOffset.UTC).toLocalDate();
        long days = ChronoUnit.DAYS.between(servedDay, appliedDay);
        return days >= 0 && days <= 7;
    }

    private WeighResponse toWeighResponse(WeighRecordRow row, boolean replayed, boolean deduped) {
        if (row == null) {
            throw ApiException.notFound("过磅记录不存在");
        }
        String caseNo = null;
        if (row.caseId() != null) {
            CaseRow caseRow = caseRepository.findById(row.caseId());
            caseNo = caseRow == null ? null : caseRow.caseNo();
        }
        return new WeighResponse(row.id(), row.plateNo(), row.stationCode(), row.weighTime(),
                row.mode(), row.totalKg(), row.limitKg(), row.overloadRate(), row.tier(), row.role(),
                caseNo, RecordRole.LATE_EVIDENCE.name().equals(row.role()), replayed, deduped);
    }

    private DecisionResponse toDecisionResponse(DecisionRow row, boolean replayed) {
        if (row == null) {
            throw ApiException.notFound("处罚决定不存在");
        }
        CaseRow caseRow = caseRepository.findById(row.caseId());
        return new DecisionResponse(row.id(), row.docNo(), caseRow == null ? null : caseRow.caseNo(),
                row.docType(), row.tier(), row.totalKg(), row.limitKg(), row.overloadRate(),
                row.fineAmount(), row.points(), row.status(), row.servedAt(), replayed);
    }

    private ReviewResponse toReviewResponse(ReviewRow row, boolean replayed) {
        if (row == null) {
            throw ApiException.notFound("复检申请不存在");
        }
        CaseRow caseRow = caseRepository.findById(row.caseId());
        return new ReviewResponse(row.id(), caseRow == null ? null : caseRow.caseNo(),
                row.decisionId(), row.status(), row.appliedAt(), row.concludedAt(), replayed);
    }

    private CaseResponse toCaseResponse(CaseRow caseRow, boolean replayed) {
        List<DecisionResponse> decisions = decisionRepository.findByCaseId(caseRow.id()).stream()
                .map(d -> toDecisionResponse(d, false))
                .toList();
        return new CaseResponse(caseRow.caseNo(), caseRow.plateNo(), caseRow.status(),
                caseRow.closedAt(), decisions, replayed);
    }
}
