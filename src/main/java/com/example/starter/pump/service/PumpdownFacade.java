package com.example.starter.pump.service;

import com.example.starter.pump.core.Fingerprints;
import com.example.starter.pump.web.dto.ArchiveRequest;
import com.example.starter.pump.web.dto.ArchiveResult;
import com.example.starter.pump.web.dto.CandidateConfirmRequest;
import com.example.starter.pump.web.dto.CandidateConfirmResult;
import com.example.starter.pump.web.dto.GateReceiptRequest;
import com.example.starter.pump.web.dto.GateReceiptResult;
import com.example.starter.pump.web.dto.LevelIngestRequest;
import com.example.starter.pump.web.dto.LevelIngestResult;
import com.example.starter.pump.web.dto.PumpCommandRequest;
import com.example.starter.pump.web.dto.PumpCommandResult;
import com.example.starter.pump.web.dto.ReplayResult;
import com.example.starter.pump.web.dto.StrongDrainView;
import com.example.starter.pump.web.dto.TideConfigRequest;
import com.example.starter.pump.web.dto.TideConfigResult;
import com.example.starter.pump.web.dto.TideReadingRequest;
import com.example.starter.pump.web.dto.TideReadingResult;
import com.example.starter.pump.web.dto.WarningRequest;
import com.example.starter.pump.web.dto.WarningResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预降业务门面：编排事务边界、requestId 幂等与各业务服务。
 *
 * <p>幂等接口（预警、启停、归档）的占位抢占、业务写库与首次结果回写处于同一事务：
 * 业务失败整体回滚；同键异参抛 409 且无任何写入。液位接口不走 requestId，
 * 但“数据入库＋自动停泵＋失联派单”在同一事务内原子提交。
 */
@Service
public class PumpdownFacade {

    private final IdempotencyTemplate idempotency;
    private final WarningService warningService;
    private final PumpCommandService pumpCommandService;
    private final LevelService levelService;
    private final ArchiveService archiveService;
    private final ReplayService replayService;
    private final TideStrongDrainService tideStrongDrainService;

    public PumpdownFacade(IdempotencyTemplate idempotency,
                          WarningService warningService,
                          PumpCommandService pumpCommandService,
                          LevelService levelService,
                          ArchiveService archiveService,
                          ReplayService replayService,
                          TideStrongDrainService tideStrongDrainService) {
        this.idempotency = idempotency;
        this.warningService = warningService;
        this.pumpCommandService = pumpCommandService;
        this.levelService = levelService;
        this.archiveService = archiveService;
        this.replayService = replayService;
        this.tideStrongDrainService = tideStrongDrainService;
    }

    /** 气象预警：橙色及以上启动预降。 */
    @Transactional
    public WarningResult handleWarning(WarningRequest request) {
        String fingerprint = Fingerprints.sha256(request.requestId(), request.zoneCode(),
                request.alertLevel(), request.alertTime());
        IdempotencyTemplate.Outcome<WarningResult> outcome = idempotency.execute(
                request.requestId(), "WARNING", fingerprint,
                HttpStatus.ACCEPTED.value(),
                () -> warningService.process(request),
                WarningResult.class);
        return withReplayed(outcome.value(), outcome.replayed());
    }

    /** 泵站启停。 */
    @Transactional
    public PumpCommandResult handlePumpCommand(PumpCommandRequest request) {
        String fingerprint = Fingerprints.sha256(request.requestId(), request.pumpdownId(),
                request.stationCode(), request.action(), request.taskTime(),
                request.gateOpening());
        IdempotencyTemplate.Outcome<PumpCommandResult> outcome = idempotency.execute(
                request.requestId(), "PUMP_TASK", fingerprint,
                HttpStatus.ACCEPTED.value(),
                () -> pumpCommandService.processManual(request),
                PumpCommandResult.class);
        PumpCommandResult value = outcome.value();
        if (outcome.replayed()) {
            return new PumpCommandResult(value.taskId(), value.sequenceId(), value.pumpdownId(),
                    value.stationCode(), value.action(), value.taskTime(), value.gateOpening(),
                    value.reason(), true);
        }
        return value;
    }

    /** 液位复测：非 requestId 幂等，但事务内原子处理去重、停泵、派单。 */
    @Transactional
    public LevelIngestResult handleLevel(LevelIngestRequest request) {
        return levelService.ingest(request);
    }

    /** 复盘归档：终态，重复归档幂等成功。 */
    @Transactional
    public ArchiveResult handleArchive(ArchiveRequest request) {
        String fingerprint = Fingerprints.sha256(request.requestId(), request.pumpdownId(),
                request.archiveTime());
        IdempotencyTemplate.Outcome<ArchiveResult> outcome = idempotency.execute(
                request.requestId(), "ARCHIVE", fingerprint,
                HttpStatus.OK.value(),
                () -> archiveService.archive(request),
                ArchiveResult.class);
        ArchiveResult value = outcome.value();
        if (outcome.replayed()) {
            return new ArchiveResult(value.pumpdownId(), value.status(), value.archivedAt(),
                    value.summary(), true);
        }
        return value;
    }

    /** 台账复算。 */
    @Transactional(readOnly = true)
    public ReplayResult replay(Long pumpdownId) {
        return replayService.replay(pumpdownId);
    }

    // ============ 潮汐顶托工况与强排联锁（新增，requestId 幂等） ============

    /** 新增/修改分区潮汐控制配置：版本从1开始、成功变更加1，版本失配409并返回当前版本。 */
    @Transactional
    public TideConfigResult handleTideConfig(TideConfigRequest request) {
        String fingerprint = Fingerprints.structured("TIDE_CONFIG", request.requestId(),
                request.zoneCode(), request.thresholdLevel(), request.expectedVersion());
        IdempotencyTemplate.Outcome<TideConfigResult> outcome = idempotency.execute(
                request.requestId(), "TIDE_CONFIG", fingerprint,
                HttpStatus.OK.value(),
                () -> tideStrongDrainService.upsertConfig(request),
                TideConfigResult.class);
        TideConfigResult value = outcome.value();
        return outcome.replayed()
                ? new TideConfigResult(value.zoneCode(), value.thresholdLevel(), value.backoff(),
                        value.regimeVersion(), value.created(), true)
                : value;
    }

    /** 潮位上送：UTC时刻唯一、滞后回线判工况、跨边界生成候选。 */
    @Transactional
    public TideReadingResult handleTideReading(TideReadingRequest request) {
        String fingerprint = Fingerprints.structured("TIDE_READING", request.requestId(),
                request.zoneCode(), request.tideTime().withOffsetSameInstant(java.time.ZoneOffset.UTC),
                request.tideLevel());
        IdempotencyTemplate.Outcome<TideReadingResult> outcome = idempotency.execute(
                request.requestId(), "TIDE_READING", fingerprint,
                HttpStatus.ACCEPTED.value(),
                () -> tideStrongDrainService.ingest(request),
                TideReadingResult.class);
        return outcome.replayed() ? tideStrongDrainService.markReplayed(outcome.value())
                : outcome.value();
    }

    /** 闸门合成到位回执：按序列＋站点唯一，只归所报候选。 */
    @Transactional
    public GateReceiptResult handleGateReceipt(GateReceiptRequest request) {
        String fingerprint = Fingerprints.structured("GATE_RECEIPT", request.requestId(),
                request.sequenceId(), request.stationCode(), request.opening(),
                request.receiptTime().withOffsetSameInstant(java.time.ZoneOffset.UTC));
        IdempotencyTemplate.Outcome<GateReceiptResult> outcome = idempotency.execute(
                request.requestId(), "GATE_RECEIPT", fingerprint,
                HttpStatus.ACCEPTED.value(),
                () -> tideStrongDrainService.receipt(request),
                GateReceiptResult.class);
        GateReceiptResult value = outcome.value();
        return outcome.replayed()
                ? new GateReceiptResult(value.sequenceId(), value.stationCode(), value.opening(),
                        value.receiptTime(), true)
                : value;
    }

    /** 强排候选确认：单事务原子联锁切换，任一前置不到位零切换。 */
    @Transactional
    public CandidateConfirmResult handleCandidateConfirm(CandidateConfirmRequest request) {
        String fingerprint = Fingerprints.structured("CANDIDATE_CONFIRM", request.requestId(),
                request.sequenceId(), request.expectedVersion(),
                request.confirmTime().withOffsetSameInstant(java.time.ZoneOffset.UTC));
        IdempotencyTemplate.Outcome<CandidateConfirmResult> outcome = idempotency.execute(
                request.requestId(), "CANDIDATE_CONFIRM", fingerprint,
                HttpStatus.OK.value(),
                () -> tideStrongDrainService.confirm(request),
                CandidateConfirmResult.class);
        CandidateConfirmResult value = outcome.value();
        return outcome.replayed()
                ? new CandidateConfirmResult(value.sequenceId(), value.zoneCode(),
                        value.direction(), value.state(), value.tideVersion(),
                        value.regimeVersion(), value.supersededSequenceId(), value.tasks(), true)
                : value;
    }

    /** 工况总览查询（只读）。 */
    @Transactional(readOnly = true)
    public StrongDrainView tideView(String zoneCode) {
        return tideStrongDrainService.view(zoneCode);
    }

    private WarningResult withReplayed(WarningResult value, boolean replayed) {
        if (!replayed) {
            return value;
        }
        return new WarningResult(value.pumpdownId(), value.zoneCode(), value.alertLevel(),
                value.triggered(), value.status(), value.startedAt(), value.targetLevel(), true);
    }
}
