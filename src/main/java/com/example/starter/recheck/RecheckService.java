package com.example.starter.recheck;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.casefile.CaseRecord;
import com.example.starter.casefile.CaseRepository;
import com.example.starter.common.ApiException;
import com.example.starter.common.Fingerprints;
import com.example.starter.penalty.PenaltyDecision;
import com.example.starter.penalty.PenaltyRepository;
import com.example.starter.penalty.PenaltyTiers;
import com.example.starter.weigh.WeighRecord;
import com.example.starter.weigh.WeighRecordRepository;

/**
 * 复检申诉服务：送达后 7 个自然日（UTC 日界）内受理一次；
 * 推翻原处罚时在同一事务内撤销原决定、退还罚款记分并生成撤销文书。
 */
@Service
public class RecheckService {

    /** 复检申请期限：送达后 7 个自然日，恰第 7 日受理。 */
    static final long WINDOW_DAYS = 7;

    private final RecheckRepository rechecks;
    private final RevocationRepository revocations;
    private final PenaltyRepository penalties;
    private final WeighRecordRepository weighRecords;
    private final CaseRepository cases;
    private final Clock clock;

    public RecheckService(RecheckRepository rechecks, RevocationRepository revocations,
            PenaltyRepository penalties, WeighRecordRepository weighRecords,
            CaseRepository cases, Clock clock) {
        this.rechecks = rechecks;
        this.revocations = revocations;
        this.penalties = penalties;
        this.weighRecords = weighRecords;
        this.cases = cases;
        this.clock = clock;
    }

    /**
     * 受理复检申请。同 requestId 同参返回首次结果，同键异参 409；
     * 同一案件仅受理一次，并发下由唯一约束保证。
     */
    @Transactional
    public RecheckResponse apply(RecheckApplyRequest req) {
        String fingerprint = Fingerprints.of(req.requestId(), req.penaltyId(), req.staticWeightKg());

        Optional<RecheckRepository.RecheckRow> existing = rechecks.findByRequestId(req.requestId());
        if (existing.isPresent()) {
            RecheckRepository.RecheckRow found = existing.get();
            if (!found.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已存在且业务字段不一致");
            }
            return toResponse(found, true);
        }

        PenaltyDecision penalty = penalties.findById(req.penaltyId())
                .orElseThrow(() -> ApiException.notFound("处罚决定不存在: " + req.penaltyId()));
        CaseRecord caseRecord = cases.findById(penalty.caseId())
                .orElseThrow(() -> ApiException.notFound("案件不存在: " + penalty.caseId()));
        if ("CLOSED".equals(caseRecord.status())) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,
                    "CASE_CLOSED", "案件已结案，不再受理复检");
        }
        if (!"ACTIVE".equals(penalty.status())) {
            throw ApiException.unprocessable("PENALTY_NOT_ACTIVE", "处罚决定已撤销，无需复检");
        }

        Instant appliedAt = Instant.now(clock);
        long days = ChronoUnit.DAYS.between(
                LocalDate.ofInstant(penalty.servedAt(), ZoneOffset.UTC),
                LocalDate.ofInstant(appliedAt, ZoneOffset.UTC));
        if (days > WINDOW_DAYS) {
            throw ApiException.unprocessable("RECHECK_OVERDUE",
                    "复检申请超出送达后 7 个自然日期限，已逾期 " + (days - WINDOW_DAYS) + " 日");
        }

        // 以静态复检重量按原限值复算超限率与分档
        WeighRecord basis = weighRecords.findById(penalty.weighRecordId())
                .orElseThrow(() -> ApiException.notFound("检测记录不存在: " + penalty.weighRecordId()));
        BigDecimal staticRate = BigDecimal.valueOf(req.staticWeightKg() - basis.limitWeightKg())
                .divide(BigDecimal.valueOf(basis.limitWeightKg()), 2, RoundingMode.HALF_UP);
        boolean staticOverLimit = staticRate.compareTo(BigDecimal.ZERO) > 0;
        String staticMeasure = staticOverLimit ? PenaltyTiers.measureFor(staticRate) : "NONE";
        boolean overturned = !staticOverLimit || !staticMeasure.equals(penalty.measure());
        String outcome = overturned ? "OVERTURNED" : "UPHELD";

        long recheckId;
        try {
            recheckId = rechecks.insert(req.requestId(), fingerprint, req.penaltyId(),
                    caseRecord.id(), req.staticWeightKg(), appliedAt, outcome);
        } catch (DuplicateKeyException e) {
            // 并发或重复申请：同 requestId 已由前置检查处理，此处为同案已有复检
            Optional<RecheckRepository.RecheckRow> byRequest = rechecks.findByRequestId(req.requestId());
            if (byRequest.isPresent() && byRequest.get().fingerprint().equals(fingerprint)) {
                return toResponse(byRequest.get(), true);
            }
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,
                    "RECHECK_ALREADY_ACCEPTED", "同一案件仅受理一次复检申请");
        }

        Long revocationId = null;
        Long replacementPenaltyId = null;
        if (overturned) {
            // 同一事务内：撤销原决定 + 生成撤销文书退还罚款记分；不得罚款已退而原决定仍生效
            int revoked = penalties.revokeIfActive(penalty.id());
            if (revoked != 1) {
                throw new IllegalStateException("撤销原处罚决定失败: " + penalty.id());
            }
            revocationId = revocations.insert(penalty.id(), recheckId,
                    penalty.fineAmount(), penalty.demeritPoints());
            if (staticOverLimit) {
                // 静态复检仍超限：按静态结果在同一事务内作出替代处罚并立新案件
                long newCaseId = cases.insertOpen(penalty.plateNo());
                PenaltyDecision replacement = new PenaltyDecision(0L,
                        req.requestId() + ":replacement",
                        Fingerprints.of(req.requestId(), "replacement", req.staticWeightKg()),
                        newCaseId, basis.id(), penalty.plateNo(), staticRate, staticMeasure,
                        PenaltyTiers.fineFor(staticRate), PenaltyTiers.pointsFor(staticRate),
                        "ACTIVE", appliedAt);
                replacementPenaltyId = penalties.insert(replacement, replacement.fingerprint());
                cases.assignPenalty(newCaseId, replacementPenaltyId);
            }
        }

        return new RecheckResponse(recheckId, req.requestId(), req.penaltyId(), caseRecord.id(),
                req.staticWeightKg(), staticRate, appliedAt, outcome,
                revocationId, replacementPenaltyId, false);
    }

    private RecheckResponse toResponse(RecheckRepository.RecheckRow row, boolean replay) {
        Long revocationId = revocations.findIdByPenaltyId(row.penaltyId()).orElse(null);
        return new RecheckResponse(row.id(), row.requestId(), row.penaltyId(), row.caseId(),
                row.staticWeightKg(), null, row.appliedAt(), row.outcome(),
                revocationId, null, replay);
    }
}
