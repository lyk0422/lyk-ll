package com.example.starter.waterlogging.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.example.starter.waterlogging.api.ArchiveRequest;
import com.example.starter.waterlogging.api.ArchiveResult;
import com.example.starter.waterlogging.api.LevelReadingRequest;
import com.example.starter.waterlogging.api.LossContactCheckRequest;
import com.example.starter.waterlogging.api.LossContactResult;
import com.example.starter.waterlogging.api.PlanView;
import com.example.starter.waterlogging.api.PumpCommandRequest;
import com.example.starter.waterlogging.api.PumpCommandResult;
import com.example.starter.waterlogging.api.ReadingResult;
import com.example.starter.waterlogging.api.WarningStartRequest;
import com.example.starter.waterlogging.domain.PlanStatus;
import com.example.starter.waterlogging.domain.PumpAction;
import com.example.starter.waterlogging.domain.RequestType;
import com.example.starter.waterlogging.exception.BusinessRuleException;
import com.example.starter.waterlogging.exception.ConflictException;
import com.example.starter.waterlogging.exception.NotFoundException;
import com.example.starter.waterlogging.po.PreDrainPlan;
import com.example.starter.waterlogging.po.PumpCommand;
import com.example.starter.waterlogging.po.PumpStation;
import com.example.starter.waterlogging.po.ReviewArchive;
import com.example.starter.waterlogging.po.WarningLevel;
import com.example.starter.waterlogging.repo.LossContactOrderRepository;
import com.example.starter.waterlogging.repo.PlanRepository;
import com.example.starter.waterlogging.repo.PumpCommandRepository;
import com.example.starter.waterlogging.repo.PumpStationRepository;
import com.example.starter.waterlogging.repo.ReadingRepository;
import com.example.starter.waterlogging.repo.ReviewArchiveRepository;
import com.example.starter.waterlogging.repo.WarningLevelRepository;
import com.example.starter.waterlogging.repo.ZoneSequenceLockRepository;
import com.example.starter.waterlogging.repo.LossContactOrderRepository.OpenResult;
import com.example.starter.waterlogging.repo.ReadingRepository.DedupResult;
import com.example.starter.waterlogging.web.IdempotentResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 城市内涝泵站联动预降业务编排。
 *
 * <p>流转：气象预警→预降决策→泵站启停→水位复测→复盘归档。
 * 所有写接口经 {@link IdempotencyService} 以 requestId 幂等；业务规则在数据库事务内生效。
 */
@Service
public class WaterLoggingService {

    /** 泵站启机最小间隔（含恰 10 分钟，防水锤）。 */
    static final Duration MIN_START_INTERVAL = Duration.ofMinutes(10);
    /** 无液位数据失联阈值（恰满 30 分钟判定）。 */
    static final Duration LOSS_CONTACT_THRESHOLD = Duration.ofMinutes(30);
    /** 停机时闸门全关开度（百分比）。 */
    private static final BigDecimal GATE_CLOSED = BigDecimal.ZERO;

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotency;
    private final WarningLevelRepository warningLevelRepository;
    private final PumpStationRepository stationRepository;
    private final PlanRepository planRepository;
    private final ZoneSequenceLockRepository zoneLockRepository;
    private final PumpCommandRepository commandRepository;
    private final ReadingRepository readingRepository;
    private final LossContactOrderRepository lossOrderRepository;
    private final ReviewArchiveRepository archiveRepository;

    public WaterLoggingService(Clock waterClock,
                               ObjectMapper objectMapper,
                               IdempotencyService idempotency,
                               WarningLevelRepository warningLevelRepository,
                               PumpStationRepository stationRepository,
                               PlanRepository planRepository,
                               ZoneSequenceLockRepository zoneLockRepository,
                               PumpCommandRepository commandRepository,
                               ReadingRepository readingRepository,
                               LossContactOrderRepository lossOrderRepository,
                               ReviewArchiveRepository archiveRepository) {
        this.clock = waterClock;
        this.objectMapper = objectMapper;
        this.idempotency = idempotency;
        this.warningLevelRepository = warningLevelRepository;
        this.stationRepository = stationRepository;
        this.planRepository = planRepository;
        this.zoneLockRepository = zoneLockRepository;
        this.commandRepository = commandRepository;
        this.readingRepository = readingRepository;
        this.lossOrderRepository = lossOrderRepository;
        this.archiveRepository = archiveRepository;
    }

    /** 气象预警：橙色及以上启动预降；预降开始与分区各站闸门调整在同一事务内完成。 */
    public IdempotentResponse<PlanView> startOnWarning(WarningStartRequest request) {
        CanonicalPayload payload = CanonicalPayload.of(request.requestId())
                .put("warningLevel", request.warningLevel())
                .put("basinZone", request.basinZone())
                .put("riverName", request.riverName())
                .putNumber("targetLevel", request.targetLevel())
                .putTime("warningTime", request.warningTime())
                .putNumber("gateOpening", request.gateOpening());
        return idempotency.run(request.requestId(), RequestType.WARNING_START, payload.asMap(),
                PlanView.class, () -> doStart(request, fingerprint(payload)));
    }

    private PlanView doStart(WarningStartRequest request, String fingerprint) {
        WarningLevel level = warningLevelRepository.findByCode(request.warningLevel())
                .orElseThrow(() -> new NotFoundException("WARNING_LEVEL_NOT_FOUND",
                        "预警级别不存在: " + request.warningLevel()));
        if (!Boolean.TRUE.equals(level.triggerPreDrain())) {
            throw new BusinessRuleException("WARNING_LEVEL_BELOW_ORANGE",
                    "预警级别未达暴雨橙色，不启动预降");
        }
        if (zoneLockRepository.findActivePlanId(request.basinZone()).isPresent()) {
            throw new ConflictException("ZONE_SEQUENCE_ACTIVE",
                    "该流域分区存在进行中的启停序列，同一分区同时刻只允许一套序列");
        }
        List<PumpStation> stations = stationRepository.findByZone(request.basinZone());
        if (stations.isEmpty()) {
            throw new BusinessRuleException("ZONE_HAS_NO_STATION",
                    "流域分区下无联动泵站: " + request.basinZone());
        }

        LocalDateTime now = LocalDateTime.now(clock);
        PreDrainPlan plan = new PreDrainPlan(
                null, request.requestId(), request.basinZone(), request.warningLevel(),
                request.riverName(), request.targetLevel(), request.warningTime(),
                request.gateOpening(), PlanStatus.DRAINING, request.warningTime(),
                null, null, fingerprint, now);
        long planId = planRepository.insert(plan);
        if (!zoneLockRepository.tryAcquire(request.basinZone(), planId, request.warningTime())) {
            // 并发预警抢同一分区：唯一约束负方事务回滚，不留下预降单。
            throw new ConflictException("ZONE_SEQUENCE_ACTIVE",
                    "该流域分区存在进行中的启停序列，同一分区同时刻只允许一套序列");
        }

        int sequenceNo = 0;
        for (PumpStation station : stations) {
            PumpStation locked = stationRepository.findByCodeForUpdate(station.stationCode())
                    .orElseThrow();
            if (PumpAction.RUNNING.equals(locked.runStatus())) {
                throw new BusinessRuleException("PUMP_ALREADY_RUNNING",
                        "泵站已在运行，无法随预警联动启机: " + locked.stationCode());
            }
            // 联动启机同样遵守防雨水锤最小启机间隔（恰 10 分钟合法）。
            commandRepository.findLastStartTimeBefore(locked.stationCode(), request.warningTime())
                    .ifPresent(lastStart -> {
                        Duration elapsed = Duration.between(lastStart, request.warningTime());
                        if (elapsed.compareTo(MIN_START_INTERVAL) < 0) {
                            throw new BusinessRuleException("START_INTERVAL_TOO_SHORT",
                                    "泵站启机间隔不得少于 10 分钟（恰 10 分钟合法）: "
                                            + locked.stationCode());
                        }
                    });
            // 启机与闸门开度调整同一事务落库，杜绝“开泵而闸门未到位”。
            stationRepository.updateState(locked.stationCode(), PumpAction.RUNNING, request.gateOpening());
            sequenceNo++;
            commandRepository.insert(new PumpCommand(
                    null, planId, locked.stationCode(), PumpAction.START, sequenceNo,
                    request.warningTime(), request.gateOpening(), true,
                    request.requestId() + "/START/" + locked.stationCode(), now));
        }
        return toPlanView(planRepository.findById(planId).orElseThrow());
    }

    /** 泵站启停：同站同时刻唯一受理，启机间隔不少于 10 分钟（恰 10 分钟合法）。 */
    public IdempotentResponse<PumpCommandResult> commandPump(PumpCommandRequest request) {
        CanonicalPayload payload = CanonicalPayload.of(request.requestId())
                .put("stationCode", request.stationCode())
                .put("action", request.action())
                .putTime("commandTime", request.commandTime())
                .putNumber("gateOpening", request.gateOpening());
        return idempotency.run(request.requestId(), RequestType.PUMP_COMMAND, payload.asMap(),
                PumpCommandResult.class, () -> doCommand(request));
    }

    private PumpCommandResult doCommand(PumpCommandRequest request) {
        PumpStation station = stationRepository.findByCodeForUpdate(request.stationCode())
                .orElseThrow(() -> new NotFoundException("STATION_NOT_FOUND",
                        "泵站不存在: " + request.stationCode()));

        Long planId = zoneLockRepository.findActivePlanId(station.basinZone()).orElseThrow(() ->
                new BusinessRuleException("NO_ACTIVE_PRE_DRAIN_PLAN",
                        "该泵站所属分区没有进行中的预降序列，不受理启停指令"));
        PreDrainPlan plan = planRepository.findByIdForUpdate(planId).orElseThrow();
        if (!PlanStatus.DRAINING.equals(plan.status())) {
            throw new ConflictException("ZONE_SEQUENCE_NOT_ACTIVE", "预降序列不在进行中");
        }

        if (commandRepository.findByStationAndTime(request.stationCode(), request.commandTime()).isPresent()) {
            throw new ConflictException("PUMP_STATION_TIME_TAKEN",
                    "同一泵站同时刻只允许一个启停任务");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        int sequenceNo = commandRepository.nextSequenceNo(planId);

        if (PumpAction.START.equals(request.action())) {
            if (request.gateOpening().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessRuleException("GATE_NOT_OPEN",
                        "启机必须同步开启出水闸门，闸门开度须大于 0");
            }
            if (PumpAction.RUNNING.equals(station.runStatus())) {
                throw new BusinessRuleException("PUMP_ALREADY_RUNNING",
                        "泵站已在运行，禁止重复启机: " + station.stationCode());
            }
            commandRepository.findLastStartTimeBefore(request.stationCode(), request.commandTime())
                    .ifPresent(lastStart -> {
                        Duration elapsed = Duration.between(lastStart, request.commandTime());
                        if (elapsed.compareTo(MIN_START_INTERVAL) < 0) {
                            throw new BusinessRuleException("START_INTERVAL_TOO_SHORT",
                                    "泵站启机间隔不得少于 10 分钟（恰 10 分钟合法）");
                        }
                    });
            // 启机与闸门调整同一事务原子完成。
            stationRepository.updateState(station.stationCode(), PumpAction.RUNNING, request.gateOpening());
            long commandId = insertCommandOrConflict(new PumpCommand(
                    null, planId, station.stationCode(), PumpAction.START, sequenceNo,
                    request.commandTime(), request.gateOpening(), true, request.requestId(), now));
            return new PumpCommandResult(commandId, planId, station.stationCode(), PumpAction.START,
                    sequenceNo, request.commandTime(), request.gateOpening(), true, PumpAction.RUNNING);
        }

        // STOP
        if (!PumpAction.RUNNING.equals(station.runStatus())) {
            throw new BusinessRuleException("PUMP_NOT_RUNNING",
                    "泵站未在运行，无法停机: " + station.stationCode());
        }
        if (request.gateOpening().compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessRuleException("GATE_NOT_CLOSED_ON_STOP",
                    "停机必须同步全关出水闸门，闸门开度须为 0");
        }
        stationRepository.updateState(station.stationCode(), PumpAction.STOPPED, request.gateOpening());
        long commandId = insertCommandOrConflict(new PumpCommand(
                null, planId, station.stationCode(), PumpAction.STOP, sequenceNo,
                request.commandTime(), request.gateOpening(), true, request.requestId(), now));
        return new PumpCommandResult(commandId, planId, station.stationCode(), PumpAction.STOP,
                sequenceNo, request.commandTime(), request.gateOpening(), true, PumpAction.STOPPED);
    }

    /**
     * 启停台账插入时若并发撞上站点+时刻唯一约束，翻译为 409 唯一受理冲突；
     * 该异常在幂等首办事务内被捕获，事务回滚后由全局处理返回 409，不留下半截状态。
     */
    private long insertCommandOrConflict(PumpCommand command) {
        try {
            return commandRepository.insert(command);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ConflictException("PUMP_STATION_TIME_TAKEN",
                    "同一泵站同时刻只允许一个启停任务");
        }
    }

    /**
     * 液位复测：站点+时刻去重；恰达目标位停泵并在全站停稳后收官；
     * 水位严格低于安全下限立即停泵（恰等下限合法继续运行）。
     */
    public IdempotentResponse<ReadingResult> reportReading(LevelReadingRequest request) {
        CanonicalPayload payload = CanonicalPayload.of(request.requestId())
                .put("stationCode", request.stationCode())
                .putTime("readingTime", request.readingTime())
                .putNumber("level", request.level());
        return idempotency.run(request.requestId(), RequestType.LEVEL_READING, payload.asMap(),
                ReadingResult.class, () -> doReport(request));
    }

    private ReadingResult doReport(LevelReadingRequest request) {
        PumpStation station = stationRepository.findByCodeForUpdate(request.stationCode())
                .orElseThrow(() -> new NotFoundException("STATION_NOT_FOUND",
                        "泵站不存在: " + request.stationCode()));
        LocalDateTime now = LocalDateTime.now(clock);

        PreDrainPlan latestPlan = planRepository.findLatestByZone(station.basinZone()).orElse(null);
        boolean archived = latestPlan != null && PlanStatus.ARCHIVED.equals(latestPlan.status());
        if (archived) {
            // 归档终态后迟到液位数据：只登记迟到事件，不参与曲线与停泵。
            DedupResult result = readingRepository.insertLateIfAbsent(
                    new com.example.starter.waterlogging.po.WaterLevelReading(
                            null, station.stationCode(), request.readingTime(), request.level(),
                            latestPlan.id(), now));
            return new ReadingResult(result.reading().id(), station.stationCode(),
                    request.readingTime(), request.level(), result.inserted(), true);
        }

        Long activePlanId = zoneLockRepository.findActivePlanId(station.basinZone()).orElse(null);
        DedupResult result = readingRepository.insertIfAbsent(
                new com.example.starter.waterlogging.po.WaterLevelReading(
                        null, station.stationCode(), request.readingTime(), request.level(),
                        activePlanId, now));
        if (!result.inserted()) {
            // 站点+时刻重复：返回既有数据，不触发任何停泵/收官副作用。
            return new ReadingResult(result.reading().id(), station.stationCode(),
                    request.readingTime(), result.reading().level(), false, false);
        }

        if (activePlanId != null) {
            PreDrainPlan activePlan = planRepository.findByIdForUpdate(activePlanId).orElse(null);
            evaluateAutoStop(activePlan, request, now);
        }
        return new ReadingResult(result.reading().id(), station.stationCode(),
                request.readingTime(), request.level(), true, false);
    }

    /**
     * 复测联动停泵规则，与液位写入同事务：
     * 水位 ≤ 目标位（恰达合法）或严格低于安全下限时停泵；全部泵站停稳且目标达成时序列收官。
     */
    private void evaluateAutoStop(PreDrainPlan plan, LevelReadingRequest request, LocalDateTime now) {
        if (plan == null || !PlanStatus.DRAINING.equals(plan.status())) {
            return;
        }
        PumpStation station = stationRepository.findByCodeForUpdate(request.stationCode()).orElseThrow();
        boolean targetReached = request.level().compareTo(plan.targetLevel()) <= 0;
        boolean belowSafetyLow = request.level().compareTo(station.safetyLowLevel()) < 0;
        boolean mustStop = targetReached || belowSafetyLow;

        if (mustStop && PumpAction.RUNNING.equals(station.runStatus())) {
            stationRepository.updateState(station.stationCode(), PumpAction.STOPPED, GATE_CLOSED);
            int sequenceNo = commandRepository.nextSequenceNo(plan.id());
            commandRepository.insert(new PumpCommand(
                    null, plan.id(), station.stationCode(), PumpAction.STOP, sequenceNo,
                    request.readingTime(), GATE_CLOSED, true,
                    "AUTO-STOP/" + request.requestId() + "/" + station.stationCode(), now));
        }

        if (targetReached) {
            List<PumpStation> zoneStations = stationRepository.findByZone(plan.basinZone()).stream()
                    .map(s -> stationRepository.findByCodeForUpdate(s.stationCode()).orElseThrow())
                    .toList();
            boolean allStopped = zoneStations.stream()
                    .allMatch(s -> PumpAction.STOPPED.equals(s.runStatus()));
            if (allStopped) {
                planRepository.markCompleted(plan.id(), request.readingTime());
                zoneLockRepository.releaseByPlan(plan.id());
            }
        }
    }

    /** 失联判定：自最后一条液位数据起恰满 30 分钟无数据即派单，同站仅一张开放单。 */
    public IdempotentResponse<LossContactResult> checkLossContact(LossContactCheckRequest request) {
        CanonicalPayload payload = CanonicalPayload.of(request.requestId())
                .put("stationCode", request.stationCode())
                .putTime("checkTime", request.checkTime());
        return idempotency.run(request.requestId(), RequestType.LOSS_CHECK, payload.asMap(),
                LossContactResult.class, () -> doCheckLoss(request));
    }

    private LossContactResult doCheckLoss(LossContactCheckRequest request) {
        stationRepository.findByCode(request.stationCode())
                .orElseThrow(() -> new NotFoundException("STATION_NOT_FOUND",
                        "泵站不存在: " + request.stationCode()));
        LocalDateTime lastReading = readingRepository
                .findLastReadingTime(request.stationCode(), request.checkTime())
                .orElse(null);
        boolean lostContact = lastReading == null
                || !Duration.between(lastReading, request.checkTime()).minus(LOSS_CONTACT_THRESHOLD).isNegative();
        if (!lostContact) {
            return new LossContactResult(request.stationCode(), lastReading, request.checkTime(),
                    false, null, null);
        }
        OpenResult openResult = lossOrderRepository.openIfAbsent(
                request.stationCode(), lastReading, request.checkTime(),
                request.requestId(), LocalDateTime.now(clock));
        return new LossContactResult(request.stationCode(), lastReading, request.checkTime(),
                true, openResult.order().id(), openResult.order().declaredAt());
    }

    /** 复盘归档：仅 COMPLETED 可归档；归档为终态，重复归档（含新 requestId）幂等成功。 */
    public IdempotentResponse<ArchiveResult> archive(ArchiveRequest request) {
        CanonicalPayload payload = CanonicalPayload.of(request.requestId())
                .put("planRequestId", request.planRequestId())
                .putTime("archiveTime", request.archiveTime())
                .put("remark", request.remark());
        return idempotency.run(request.requestId(), RequestType.REVIEW_ARCHIVE, payload.asMap(),
                ArchiveResult.class, () -> doArchive(request, fingerprint(payload)));
    }

    private ArchiveResult doArchive(ArchiveRequest request, String fingerprint) {
        PreDrainPlan plan = planRepository.findByRequestId(request.planRequestId())
                .orElseThrow(() -> new NotFoundException("PLAN_NOT_FOUND",
                        "预降单不存在: " + request.planRequestId()));
        PreDrainPlan locked = planRepository.findByIdForUpdate(plan.id()).orElseThrow();

        ReviewArchive existing = archiveRepository.findByPlanId(locked.id()).orElse(null);
        if (existing != null || PlanStatus.ARCHIVED.equals(locked.status())) {
            // 终态重复归档：幂等成功，返回首次归档结果。
            return new ArchiveResult(locked.id(), locked.requestId(), PlanStatus.ARCHIVED,
                    existing != null ? existing.archivedAt() : locked.archivedAt(),
                    existing != null ? existing.remark() : request.remark());
        }
        if (PlanStatus.DRAINING.equals(locked.status())) {
            throw new BusinessRuleException("PLAN_NOT_COMPLETED",
                    "预降序列尚未完成（仍有泵站运行/目标位未达成），不能复盘归档");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        archiveRepository.insert(new ReviewArchive(
                null, locked.id(), request.requestId(), fingerprint,
                request.archiveTime(), request.remark(), now));
        planRepository.markArchived(locked.id(), request.archiveTime());
        return new ArchiveResult(locked.id(), locked.requestId(), PlanStatus.ARCHIVED,
                request.archiveTime(), request.remark());
    }

    private PlanView toPlanView(PreDrainPlan plan) {
        List<PlanView.StationStateView> stations = stationRepository.findByZone(plan.basinZone()).stream()
                .map(s -> new PlanView.StationStateView(
                        s.stationCode(), s.stationName(), s.safetyLowLevel(),
                        s.runStatus(), s.gateOpening()))
                .toList();
        return new PlanView(plan.id(), plan.requestId(), plan.basinZone(), plan.warningLevelCode(),
                plan.riverName(), plan.targetLevel(), plan.warningTime(), plan.gateOpening(),
                plan.status(), plan.startedAt(), plan.completedAt(), plan.archivedAt(), stations);
    }

    private String fingerprint(CanonicalPayload payload) {
        try {
            return FingerprintUtil.sha256(objectMapper.writeValueAsString(payload.asMap()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("指纹序列化失败", e);
        }
    }
}
