package com.example.starter.pump.service;

import com.example.starter.pump.core.Fingerprints;
import com.example.starter.pump.web.dto.CandidateConfirmRequest;
import com.example.starter.pump.web.dto.CandidateConfirmResult;
import com.example.starter.pump.web.dto.CandidateDetailResult;
import com.example.starter.pump.web.dto.GateReceiptRequest;
import com.example.starter.pump.web.dto.GateReceiptResult;
import com.example.starter.pump.web.dto.ModeTrajectoryResult;
import com.example.starter.pump.web.dto.TideControlCreateRequest;
import com.example.starter.pump.web.dto.TideControlResult;
import com.example.starter.pump.web.dto.TideControlUpdateRequest;
import com.example.starter.pump.web.dto.TideIngestRequest;
import com.example.starter.pump.web.dto.TideIngestResult;
import com.example.starter.pump.web.dto.TideReadingListResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 潮汐工况业务门面：编排事务边界、requestId 幂等与潮汐业务服务。
 *
 * <p>与预降门面一致：占位抢占、业务写库与首次结果回写处于同一事务，业务失败整体
 * 回滚不占键；同键同参重放首次冻结结果，同键异参 409。指纹采用结构化编码，
 * 覆盖 requestId、版本、集合与精确时刻，字段含分隔符也不会歧义。
 */
@Service
public class TideFacade {

    private final IdempotencyTemplate idempotency;
    private final ModeSwitchService modeSwitchService;

    public TideFacade(IdempotencyTemplate idempotency, ModeSwitchService modeSwitchService) {
        this.idempotency = idempotency;
        this.modeSwitchService = modeSwitchService;
    }

    /** 创建分区潮汐控制配置。 */
    @Transactional
    public TideControlResult createControl(TideControlCreateRequest request) {
        String fingerprint = Fingerprints.structured(request.requestId(), request.zoneCode(),
                request.threshold());
        IdempotencyTemplate.Outcome<TideControlResult> outcome = idempotency.execute(
                request.requestId(), "TIDE_CONTROL", fingerprint,
                HttpStatus.ACCEPTED.value(),
                () -> modeSwitchService.createControl(request),
                TideControlResult.class);
        return withReplayed(outcome.value(), outcome.replayed());
    }

    /** 更新顶托门槛：携带期望版本。 */
    @Transactional
    public TideControlResult updateThreshold(String zoneCode, TideControlUpdateRequest request) {
        String fingerprint = Fingerprints.structured(request.requestId(), zoneCode,
                request.threshold(), request.expectedVersion());
        IdempotencyTemplate.Outcome<TideControlResult> outcome = idempotency.execute(
                request.requestId(), "TIDE_CONTROL", fingerprint,
                HttpStatus.ACCEPTED.value(),
                () -> modeSwitchService.updateThreshold(zoneCode, request),
                TideControlResult.class);
        return withReplayed(outcome.value(), outcome.replayed());
    }

    /** 潮位上送：去重、迟到留证据、越限生成候选。 */
    @Transactional
    public TideIngestResult ingestTide(TideIngestRequest request) {
        String fingerprint = Fingerprints.structured(request.requestId(), request.zoneCode(),
                request.readingTime(), request.level());
        IdempotencyTemplate.Outcome<TideIngestResult> outcome = idempotency.execute(
                request.requestId(), "TIDE_READING", fingerprint,
                HttpStatus.ACCEPTED.value(),
                () -> modeSwitchService.ingestTide(request),
                TideIngestResult.class);
        TideIngestResult value = outcome.value();
        if (outcome.replayed()) {
            return new TideIngestResult(value.readingId(), value.zoneCode(), value.readingTime(),
                    value.accepted(), value.deduplicated(), value.late(), value.candidateId(),
                    value.direction(), true);
        }
        return value;
    }

    /** 闸门合成到位回执。 */
    @Transactional
    public GateReceiptResult submitReceipt(Long candidateId, GateReceiptRequest request) {
        String fingerprint = Fingerprints.structured(request.requestId(), candidateId,
                request.stationCode(), request.opening());
        IdempotencyTemplate.Outcome<GateReceiptResult> outcome = idempotency.execute(
                request.requestId(), "GATE_RECEIPT", fingerprint,
                HttpStatus.ACCEPTED.value(),
                () -> modeSwitchService.submitReceipt(candidateId, request),
                GateReceiptResult.class);
        GateReceiptResult value = outcome.value();
        if (outcome.replayed()) {
            return new GateReceiptResult(value.receiptId(), value.candidateId(),
                    value.stationCode(), value.opening(), true);
        }
        return value;
    }

    /** 确认切换候选：原子启用新序列、终结旧序列并写各站任务。 */
    @Transactional
    public CandidateConfirmResult confirm(Long candidateId, CandidateConfirmRequest request) {
        String fingerprint = Fingerprints.structured(request.requestId(), candidateId,
                request.expectedVersion(), request.switchTime());
        IdempotencyTemplate.Outcome<CandidateConfirmResult> outcome = idempotency.execute(
                request.requestId(), "TIDE_CONFIRM", fingerprint,
                HttpStatus.ACCEPTED.value(),
                () -> modeSwitchService.confirm(candidateId, request),
                CandidateConfirmResult.class);
        CandidateConfirmResult value = outcome.value();
        if (outcome.replayed()) {
            return new CandidateConfirmResult(value.candidateId(), value.zoneCode(),
                    value.direction(), value.sequenceId(), value.taskIds(), value.newVersion(),
                    value.mode(), true);
        }
        return value;
    }

    /** 分区潮位查询。 */
    @Transactional(readOnly = true)
    public TideReadingListResult listReadings(String zoneCode) {
        return modeSwitchService.listReadings(zoneCode);
    }

    /** 候选确认查询。 */
    @Transactional(readOnly = true)
    public CandidateDetailResult candidateDetail(Long candidateId) {
        return modeSwitchService.candidateDetail(candidateId);
    }

    /** 分区工况轨迹查询。 */
    @Transactional(readOnly = true)
    public ModeTrajectoryResult trajectory(String zoneCode) {
        return modeSwitchService.trajectory(zoneCode);
    }

    private TideControlResult withReplayed(TideControlResult value, boolean replayed) {
        if (!replayed) {
            return value;
        }
        return new TideControlResult(value.zoneCode(), value.threshold(), value.mode(),
                value.version(), value.updatedAt(), true);
    }
}
