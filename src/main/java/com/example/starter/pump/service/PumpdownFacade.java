package com.example.starter.pump.service;

import com.example.starter.pump.core.Fingerprints;
import com.example.starter.pump.web.dto.ArchiveRequest;
import com.example.starter.pump.web.dto.ArchiveResult;
import com.example.starter.pump.web.dto.LevelIngestRequest;
import com.example.starter.pump.web.dto.LevelIngestResult;
import com.example.starter.pump.web.dto.PumpCommandRequest;
import com.example.starter.pump.web.dto.PumpCommandResult;
import com.example.starter.pump.web.dto.ReplayResult;
import com.example.starter.pump.web.dto.TideCandidateView;
import com.example.starter.pump.web.dto.TideConfigRequest;
import com.example.starter.pump.web.dto.TideConfigResult;
import com.example.starter.pump.web.dto.TideConfigView;
import com.example.starter.pump.web.dto.TideConfirmRequest;
import com.example.starter.pump.web.dto.TideConfirmResult;
import com.example.starter.pump.web.dto.TideLevelRequest;
import com.example.starter.pump.web.dto.TideLevelResult;
import com.example.starter.pump.web.dto.TideLevelView;
import com.example.starter.pump.web.dto.TideTrailView;
import com.example.starter.pump.web.dto.WarningRequest;
import com.example.starter.pump.web.dto.WarningResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

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
    private final TideService tideService;
    private final TideSwitchService tideSwitchService;
    private final TideQueryService tideQueryService;

    public PumpdownFacade(IdempotencyTemplate idempotency,
                          WarningService warningService,
                          PumpCommandService pumpCommandService,
                          LevelService levelService,
                          ArchiveService archiveService,
                          ReplayService replayService,
                          TideService tideService,
                          TideSwitchService tideSwitchService,
                          TideQueryService tideQueryService) {
        this.idempotency = idempotency;
        this.warningService = warningService;
        this.pumpCommandService = pumpCommandService;
        this.levelService = levelService;
        this.archiveService = archiveService;
        this.replayService = replayService;
        this.tideService = tideService;
        this.tideSwitchService = tideSwitchService;
        this.tideQueryService = tideQueryService;
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

    /** 分区潮汐控制配置：新建（版本从1开始）或按 expectedVersion 修改（成功加1）。 */
    @Transactional
    public TideConfigResult handleTideConfig(TideConfigRequest request) {
        String fingerprint = Fingerprints.sha256(request.requestId(), request.zoneCode(),
                request.backwaterThreshold(), request.expectedVersion());
        IdempotencyTemplate.Outcome<TideConfigResult> outcome = idempotency.execute(
                request.requestId(), "TIDE_CONFIG", fingerprint,
                HttpStatus.OK.value(),
                () -> tideService.upsertConfig(request),
                TideConfigResult.class);
        TideConfigResult value = outcome.value();
        if (outcome.replayed()) {
            return new TideConfigResult(value.zoneCode(), value.backwaterThreshold(),
                    value.currentMode(), value.version(), true);
        }
        return value;
    }

    /** 潮位上送：去重、迟到证据、滞回判定与候选生成同事务。 */
    @Transactional
    public TideLevelResult handleTideLevel(TideLevelRequest request) {
        String fingerprint = Fingerprints.sha256(request.requestId(), request.zoneCode(),
                request.tideLevel(), request.observedAt(), request.expectedVersion());
        IdempotencyTemplate.Outcome<TideLevelResult> outcome = idempotency.execute(
                request.requestId(), "TIDE_LEVEL", fingerprint,
                HttpStatus.ACCEPTED.value(),
                () -> tideService.ingest(request),
                TideLevelResult.class);
        TideLevelResult value = outcome.value();
        if (outcome.replayed()) {
            return new TideLevelResult(value.readingId(), value.zoneCode(), value.observedAt(),
                    value.tideLevel(), value.accepted(), value.deduplicated(), value.late(),
                    value.decision(), value.candidateId(), value.currentVersion(), true);
        }
        return value;
    }

    /** 强排切换候选确认：全部校验通过才原子切换，任一不到位零切换。 */
    @Transactional
    public TideConfirmResult handleTideConfirm(TideConfirmRequest request) {
        List<Object> parts = new ArrayList<>(List.of(request.requestId(), request.candidateId(),
                request.expectedVersion(), request.confirmTime()));
        for (TideConfirmRequest.GateReceipt receipt : request.gateReceipts()) {
            parts.add(receipt.stationCode());
            parts.add(receipt.opening());
        }
        String fingerprint = Fingerprints.sha256(parts.toArray());
        IdempotencyTemplate.Outcome<TideConfirmResult> outcome = idempotency.execute(
                request.requestId(), "TIDE_SWITCH", fingerprint,
                HttpStatus.ACCEPTED.value(),
                () -> tideSwitchService.confirm(request),
                TideConfirmResult.class);
        TideConfirmResult value = outcome.value();
        if (outcome.replayed()) {
            return new TideConfirmResult(value.candidateId(), value.zoneCode(),
                    value.sequenceId(), value.targetMode(), value.version(), value.switchedAt(),
                    value.taskIds(), true);
        }
        return value;
    }

    /** 分区潮汐配置查询。 */
    @Transactional(readOnly = true)
    public TideConfigView tideConfig(String zoneCode) {
        return tideQueryService.configView(zoneCode);
    }

    /** 分区潮位曲线与迟到证据查询。 */
    @Transactional(readOnly = true)
    public TideLevelView tideLevels(String zoneCode) {
        return tideQueryService.levelView(zoneCode);
    }

    /** 单个切换候选查询。 */
    @Transactional(readOnly = true)
    public TideCandidateView tideCandidate(Long candidateId) {
        return tideQueryService.candidateView(candidateId);
    }

    /** 分区全部切换候选查询。 */
    @Transactional(readOnly = true)
    public List<TideCandidateView> tideCandidates(String zoneCode) {
        return tideQueryService.candidatesOfZone(zoneCode);
    }

    /** 分区工况轨迹查询。 */
    @Transactional(readOnly = true)
    public TideTrailView tideTrail(String zoneCode) {
        return tideQueryService.trailView(zoneCode);
    }

    private WarningResult withReplayed(WarningResult value, boolean replayed) {
        if (!replayed) {
            return value;
        }
        return new WarningResult(value.pumpdownId(), value.zoneCode(), value.alertLevel(),
                value.triggered(), value.status(), value.startedAt(), value.targetLevel(), true);
    }
}
