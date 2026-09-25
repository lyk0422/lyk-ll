package com.example.starter.pump.service;

import com.example.starter.pump.core.Fingerprints;
import com.example.starter.pump.web.dto.ArchiveRequest;
import com.example.starter.pump.web.dto.ArchiveResult;
import com.example.starter.pump.web.dto.LevelIngestRequest;
import com.example.starter.pump.web.dto.LevelIngestResult;
import com.example.starter.pump.web.dto.PumpCommandRequest;
import com.example.starter.pump.web.dto.PumpCommandResult;
import com.example.starter.pump.web.dto.ReplayResult;
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

    public PumpdownFacade(IdempotencyTemplate idempotency,
                          WarningService warningService,
                          PumpCommandService pumpCommandService,
                          LevelService levelService,
                          ArchiveService archiveService,
                          ReplayService replayService) {
        this.idempotency = idempotency;
        this.warningService = warningService;
        this.pumpCommandService = pumpCommandService;
        this.levelService = levelService;
        this.archiveService = archiveService;
        this.replayService = replayService;
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

    private WarningResult withReplayed(WarningResult value, boolean replayed) {
        if (!replayed) {
            return value;
        }
        return new WarningResult(value.pumpdownId(), value.zoneCode(), value.alertLevel(),
                value.triggered(), value.status(), value.startedAt(), value.targetLevel(), true);
    }
}
