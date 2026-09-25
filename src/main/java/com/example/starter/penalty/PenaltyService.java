package com.example.starter.penalty;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.casefile.CaseRepository;
import com.example.starter.common.ApiException;
import com.example.starter.common.Fingerprints;
import com.example.starter.weigh.WeighRecord;
import com.example.starter.weigh.WeighRecordRepository;

/**
 * 处罚决定服务：依据检测记录超限率分档作出处置，同时开立案件。
 */
@Service
public class PenaltyService {

    private final PenaltyRepository penalties;
    private final WeighRecordRepository weighRecords;
    private final CaseRepository cases;
    private final Clock clock;

    public PenaltyService(PenaltyRepository penalties, WeighRecordRepository weighRecords,
            CaseRepository cases, Clock clock) {
        this.penalties = penalties;
        this.weighRecords = weighRecords;
        this.cases = cases;
        this.clock = clock;
    }

    /**
     * 作出处罚决定。同 requestId 同参返回首次结果，同键异参 409；
     * 动态与静态复检不一致时以静态复检为准。
     */
    @Transactional
    public PenaltyResponse create(PenaltyRequest req) {
        String fingerprint = Fingerprints.of(req.requestId(), req.weighRecordId());

        Optional<PenaltyDecision> existing = penalties.findByRequestId(req.requestId());
        if (existing.isPresent()) {
            PenaltyDecision found = existing.get();
            if (!found.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已存在且业务字段不一致");
            }
            return PenaltyResponse.of(found, true);
        }

        WeighRecord record = weighRecords.findById(req.weighRecordId())
                .orElseThrow(() -> ApiException.notFound("检测记录不存在: " + req.weighRecordId()));
        // 静态复检优先：若同时刻存在静态记录，以静态记录为判定依据
        if ("DYNAMIC".equals(record.weighType())) {
            Optional<WeighRecord> staticRecord = weighRecords.findStaticAt(
                    record.plateNo(), record.stationId(), record.weighedAt());
            if (staticRecord.isPresent()) {
                record = staticRecord.get();
            }
        }
        if (record.lateEvidence()) {
            throw ApiException.unprocessable("LATE_EVIDENCE", "结案后迟到数据仅入证据备查，不得据以处罚");
        }
        if (!record.overLimit()) {
            throw ApiException.unprocessable("NOT_OVER_LIMIT", "检测记录未超限，无需处罚");
        }

        long caseId = cases.insertOpen(record.plateNo());
        PenaltyDecision decision = new PenaltyDecision(0L, req.requestId(), fingerprint, caseId,
                record.id(), record.plateNo(), record.overRate(),
                PenaltyTiers.measureFor(record.overRate()),
                PenaltyTiers.fineFor(record.overRate()),
                PenaltyTiers.pointsFor(record.overRate()),
                "ACTIVE", Instant.now(clock));
        long id = penalties.insert(decision, fingerprint);
        cases.assignPenalty(caseId, id);
        return PenaltyResponse.of(penalties.findById(id).orElseThrow(), false);
    }

    /**
     * 按编号查询处罚决定。
     */
    @Transactional(readOnly = true)
    public PenaltyResponse get(long id) {
        return PenaltyResponse.of(penalties.findById(id)
                .orElseThrow(() -> ApiException.notFound("处罚决定不存在: " + id)), false);
    }
}
