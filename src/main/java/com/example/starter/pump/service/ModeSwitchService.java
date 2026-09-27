package com.example.starter.pump.service;

import com.example.starter.pump.core.TimeProvider;
import com.example.starter.pump.domain.GateReceipt;
import com.example.starter.pump.domain.GateTarget;
import com.example.starter.pump.domain.PumpStation;
import com.example.starter.pump.domain.PumpdownEvent;
import com.example.starter.pump.domain.SwitchCandidate;
import com.example.starter.pump.domain.TideControl;
import com.example.starter.pump.error.ApiException;
import com.example.starter.pump.repo.CatalogRepository;
import com.example.starter.pump.repo.LevelReadingRepository;
import com.example.starter.pump.repo.ModeSwitchRepository;
import com.example.starter.pump.repo.PumpTaskRepository;
import com.example.starter.pump.repo.PumpdownEventRepository;
import com.example.starter.pump.repo.TideControlRepository;
import com.example.starter.pump.repo.TideReadingRepository;
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
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 潮汐顶托工况切换与强排联锁。
 *
 * <p>规则：潮位 &ge; 顶托门槛进入强排（恰等进入）；回落到门槛减0.2米以下才退出
 * （恰在回落线仍保持强排）。越限生成候选序列，固定潮位版本与全部相关站闸门目标；
 * 确认时在事务内校验分区工况版本、各站无其他占用、闸门到位回执齐全，任一不到位
 * 零切换。迟到较旧潮位只留证据不让工况倒退。未配置潮汐控制的分区保持原行为。
 */
@Service
public class ModeSwitchService {

    /** 相邻两次启机的最小间隔（与手动启泵一致，不因强排放宽）。 */
    static final Duration MIN_START_INTERVAL = Duration.ofMinutes(10);

    private final TideControlRepository controlRepository;
    private final TideReadingRepository tideRepository;
    private final ModeSwitchRepository switchRepository;
    private final CatalogRepository catalogRepository;
    private final PumpdownEventRepository eventRepository;
    private final PumpTaskRepository taskRepository;
    private final LevelReadingRepository levelRepository;
    private final TimeProvider timeProvider;

    public ModeSwitchService(TideControlRepository controlRepository,
                             TideReadingRepository tideRepository,
                             ModeSwitchRepository switchRepository,
                             CatalogRepository catalogRepository,
                             PumpdownEventRepository eventRepository,
                             PumpTaskRepository taskRepository,
                             LevelReadingRepository levelRepository,
                             TimeProvider timeProvider) {
        this.controlRepository = controlRepository;
        this.tideRepository = tideRepository;
        this.switchRepository = switchRepository;
        this.catalogRepository = catalogRepository;
        this.eventRepository = eventRepository;
        this.taskRepository = taskRepository;
        this.levelRepository = levelRepository;
        this.timeProvider = timeProvider;
    }

    // ---------- 潮汐控制配置 ----------

    /** 创建分区潮汐控制配置（幂等首次执行体）。 */
    public TideControlResult createControl(TideControlCreateRequest request) {
        if (!catalogRepository.zoneExists(request.zoneCode())) {
            throw ApiException.notFound("ZONE_NOT_FOUND")
                    .raise("流域分区不存在: " + request.zoneCode());
        }
        if (controlRepository.find(request.zoneCode()).isPresent()) {
            throw ApiException.unprocessable("TIDE_CONTROL_EXISTS")
                    .raise("分区 " + request.zoneCode() + " 已配置潮汐控制，请使用更新接口");
        }
        LocalDateTime now = timeProvider.now();
        controlRepository.insert(request.zoneCode(), request.threshold(), now);
        return new TideControlResult(request.zoneCode(), request.threshold(), "NORMAL", 1,
                now, false);
    }

    /** 更新顶托门槛（幂等首次执行体）：版本失配拒绝并回传最新版本。 */
    public TideControlResult updateThreshold(String zoneCode, TideControlUpdateRequest request) {
        TideControl control = requireControl(zoneCode);
        if (!controlRepository.updateThreshold(zoneCode, request.threshold(),
                request.expectedVersion(), timeProvider.now())) {
            throw versionMismatch(zoneCode, request.expectedVersion());
        }
        TideControl updated = controlRepository.find(zoneCode)
                .orElseThrow(() -> new IllegalStateException("潮汐配置更新后读取为空: " + zoneCode));
        return new TideControlResult(updated.zoneCode(), updated.threshold(), updated.mode(),
                updated.version(), updated.updatedAt(), false);
    }

    // ---------- 潮位上送 ----------

    /** 潮位上送（幂等首次执行体）：去重、迟到留证据、越限生成候选。 */
    public TideIngestResult ingestTide(TideIngestRequest request) {
        TideControl control = requireControl(request.zoneCode());
        LocalDateTime readingTimeUtc = request.readingTime()
                .withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime receivedAt = timeProvider.now();

        // 分区＋UTC时刻去重：命中则原样返回，不产生任何新副作用
        Long existingId = tideRepository.findIdByZoneAndTime(request.zoneCode(), readingTimeUtc);
        if (existingId != null) {
            return new TideIngestResult(existingId, request.zoneCode(), readingTimeUtc,
                    false, true, false, null, null, false);
        }

        // 迟到较旧潮位：只留证据，不让当前工况倒退
        LocalDateTime latest = tideRepository.latestReadingTime(request.zoneCode());
        boolean late = latest != null && readingTimeUtc.isBefore(latest);

        Long readingId = tideRepository.insert(request.zoneCode(), readingTimeUtc,
                request.level(), late, receivedAt);
        if (readingId == null) {
            // 并发下分区＋时刻被抢占，按去重返回首次ID
            Long concurrentId = tideRepository.findIdByZoneAndTime(request.zoneCode(), readingTimeUtc);
            return new TideIngestResult(concurrentId, request.zoneCode(), readingTimeUtc,
                    false, true, false, null, null, false);
        }
        if (late) {
            return new TideIngestResult(readingId, request.zoneCode(), readingTimeUtc,
                    true, false, true, null, null, false);
        }

        SwitchCandidate candidate = evaluateAndGenerateCandidate(control, readingId,
                readingTimeUtc, request.level());
        if (candidate == null) {
            return new TideIngestResult(readingId, request.zoneCode(), readingTimeUtc,
                    true, false, false, null, null, false);
        }
        return new TideIngestResult(readingId, request.zoneCode(), readingTimeUtc,
                true, false, false, candidate.id(), candidate.direction(), false);
    }

    /** 越限判定：恰达门槛进入强排；严格低于回落线退出强排；回差带内不动作。 */
    private SwitchCandidate evaluateAndGenerateCandidate(TideControl control, Long readingId,
                                                         LocalDateTime readingTimeUtc,
                                                         BigDecimal level) {
        String direction;
        if (!control.forced() && level.compareTo(control.threshold()) >= 0) {
            direction = "ENTER_FORCED";
        } else if (control.forced() && level.compareTo(control.exitLine()) < 0) {
            direction = "EXIT_FORCED";
        } else {
            return null;
        }
        // 同一分区同一时刻只允许一个待确认候选：已有待确认候选时不再重复生成
        if (switchRepository.findPendingByZone(control.zoneCode()).isPresent()) {
            return null;
        }
        List<PumpStation> stations = catalogRepository.stationsOfZone(control.zoneCode());
        if (stations.isEmpty()) {
            throw ApiException.unprocessable("ZONE_WITHOUT_STATION")
                    .raise("分区下无泵站，无法生成强排候选: " + control.zoneCode());
        }
        Long candidateId = switchRepository.insertCandidate(control.zoneCode(), direction,
                readingId, readingTimeUtc, level, control.version(), timeProvider.now());
        for (PumpStation station : stations) {
            BigDecimal target = "ENTER_FORCED".equals(direction)
                    ? station.gateStartOpening() : BigDecimal.ZERO.setScale(2);
            switchRepository.insertTarget(candidateId, station.code(), target);
        }
        return switchRepository.findCandidate(candidateId)
                .orElseThrow(() -> new IllegalStateException("切换候选生成后读取为空: " + candidateId));
    }

    // ---------- 闸门到位回执 ----------

    /** 闸门合成到位回执（幂等首次执行体）：仅待确认候选可收，开度须与固定目标一致。 */
    public GateReceiptResult submitReceipt(Long candidateId, GateReceiptRequest request) {
        SwitchCandidate candidate = requireCandidate(candidateId);
        if (!candidate.pending()) {
            throw ApiException.unprocessable("CANDIDATE_NOT_PENDING")
                    .raise("切换候选 " + candidateId + " 已非待确认状态，回执只能归属原候选，不可改投");
        }
        Map<String, GateTarget> targets = switchRepository.listTargets(candidateId).stream()
                .collect(Collectors.toMap(GateTarget::stationCode, Function.identity()));
        GateTarget target = targets.get(request.stationCode());
        if (target == null) {
            throw ApiException.unprocessable("STATION_NOT_IN_CANDIDATE")
                    .raise("泵站 " + request.stationCode() + " 不在候选 " + candidateId + " 的闸门目标中");
        }
        if (request.opening().compareTo(target.targetOpening()) != 0) {
            throw ApiException.unprocessable("GATE_OPENING_MISMATCH")
                    .raise("泵站 " + request.stationCode() + " 回执开度 " + request.opening()
                            + " 与候选固定目标 " + target.targetOpening() + " 不一致");
        }
        Long receiptId = switchRepository.insertReceipt(candidateId, request.stationCode(),
                request.opening(), timeProvider.now());
        if (receiptId == null) {
            throw ApiException.concurrentConflict("GATE_RECEIPT_DUPLICATED",
                    "泵站 " + request.stationCode() + " 在候选 " + candidateId + " 的回执已存在，唯一受理");
        }
        return new GateReceiptResult(receiptId, candidateId, request.stationCode(),
                request.opening(), false);
    }

    // ---------- 候选确认（原子切换） ----------

    /**
     * 确认切换候选（幂等首次执行体）。
     *
     * <p>事务内依次校验：候选待确认、分区工况版本与候选固定版本一致、闸门回执齐全、
     * 分区存在活动预降事件、各站无其他占用且满足10分钟启机间隔/预降目标/安全下限；
     * 全部通过才原子启用新序列、终结旧序列并写各站任务，任一不到位零切换。
     */
    public CandidateConfirmResult confirm(Long candidateId, CandidateConfirmRequest request) {
        SwitchCandidate candidate = requireCandidate(candidateId);
        if (!candidate.pending()) {
            throw ApiException.unprocessable("CANDIDATE_NOT_PENDING")
                    .raise("切换候选 " + candidateId + " 已非待确认状态（当前 " + candidate.status() + "）");
        }
        TideControl control = requireControl(candidate.zoneCode());
        if (control.version() != request.expectedVersion()
                || control.version() != candidate.pinnedVersion()) {
            throw versionMismatch(candidate.zoneCode(), request.expectedVersion());
        }

        List<GateTarget> targets = switchRepository.listTargets(candidateId);
        Map<String, GateReceipt> receipts = switchRepository.listReceipts(candidateId).stream()
                .collect(Collectors.toMap(GateReceipt::stationCode, Function.identity()));
        for (GateTarget target : targets) {
            if (!receipts.containsKey(target.stationCode())) {
                throw ApiException.unprocessable("GATE_RECEIPT_INCOMPLETE")
                        .raise("泵站 " + target.stationCode() + " 闸门合成到位回执缺失，零切换");
            }
        }

        PumpdownEvent event = eventRepository.findActiveByZone(candidate.zoneCode())
                .orElseThrow(() -> ApiException.unprocessable("NO_ACTIVE_PUMPDOWN")
                        .raise("分区 " + candidate.zoneCode() + " 无进行中的预降事件，工况切换不落任务"));
        List<PumpStation> stations = catalogRepository.stationsOfZone(candidate.zoneCode());
        Map<String, BigDecimal> targetByStation = targets.stream()
                .collect(Collectors.toMap(GateTarget::stationCode, GateTarget::targetOpening));

        List<TaskPlan> plans = "ENTER_FORCED".equals(candidate.direction())
                ? planEnterForced(event, stations, targetByStation, request.switchTime())
                : planExitForced(event, stations, request.switchTime());

        // 原子启用：终结旧序列 → 启用新序列 → 写各站任务（任一失败整体回滚）
        LocalDateTime now = timeProvider.now();
        taskRepository.terminateActiveSequences(event.id(), candidate.zoneCode(), now);
        Long sequenceId = taskRepository.findOrCreateSequence(event.id(), candidate.zoneCode(),
                request.switchTime(), now);
        taskRepository.activateSequence(sequenceId);
        List<Long> taskIds = new ArrayList<>();
        for (TaskPlan plan : plans) {
            Long taskId = taskRepository.insertTask(sequenceId, event.id(), plan.station().code(),
                    plan.action(), request.switchTime(), plan.reason());
            if (taskId == null) {
                throw ApiException.concurrentConflict("STATION_TIME_CONFLICT",
                        "泵站 " + plan.station().code() + " 在 " + request.switchTime()
                                + " 已存在启停任务，并发切换唯一受理");
            }
            if (plan.gateOpening() != null) {
                taskRepository.insertGateLog(taskId, plan.station().code(), plan.gateOpening(),
                        request.switchTime());
            }
            taskIds.add(taskId);
        }

        String newMode = "ENTER_FORCED".equals(candidate.direction()) ? "FORCED" : "NORMAL";
        if (!controlRepository.applyModeSwitch(candidate.zoneCode(), newMode,
                candidate.pinnedVersion(), now)) {
            throw versionMismatch(candidate.zoneCode(), request.expectedVersion());
        }
        if (!switchRepository.transitionFromPending(candidateId, "CONFIRMED")) {
            throw ApiException.concurrentConflict("CANDIDATE_STATE_CONFLICT",
                    "切换候选 " + candidateId + " 已被并发确认，唯一受理");
        }
        int newVersion = candidate.pinnedVersion() + 1;
        switchRepository.insertTrajectory(candidate.zoneCode(), candidateId, control.mode(),
                newMode, newVersion, candidate.tideTime(), request.switchTime(), now);

        return new CandidateConfirmResult(candidateId, candidate.zoneCode(), candidate.direction(),
                sequenceId, taskIds, newVersion, newMode, false);
    }

    /** 进入强排：校验各站无占用、启机间隔、预降目标与安全下限，全部通过才排定启泵计划。 */
    private List<TaskPlan> planEnterForced(PumpdownEvent event, List<PumpStation> stations,
                                           Map<String, BigDecimal> targetByStation,
                                           LocalDateTime switchTime) {
        List<TaskPlan> plans = new ArrayList<>();
        for (PumpStation station : stations) {
            PumpTaskRepository.LastTask last = taskRepository.latestTask(station.code(), event.id());
            if (last != null && last.running()) {
                throw ApiException.unprocessable("STATION_OCCUPIED")
                        .raise("泵站 " + station.code() + " 已在运行被占用，强排切换零切换");
            }
            if (taskRepository.taskExistsAt(station.code(), switchTime)) {
                throw ApiException.unprocessable("STATION_OCCUPIED")
                        .raise("泵站 " + station.code() + " 在 " + switchTime + " 已有启停任务占用，零切换");
            }
            LocalDateTime latestStart = taskRepository.latestStartTimeBefore(station.code(), switchTime);
            if (latestStart != null
                    && Duration.between(latestStart, switchTime).compareTo(MIN_START_INTERVAL) < 0) {
                throw ApiException.unprocessable("START_INTERVAL_TOO_SHORT")
                        .raise("泵站 " + station.code() + " 距上次启泵不足10分钟，强排不予放宽，零切换");
            }
            BigDecimal latestLevel = levelRepository.latestLevelAtOrBefore(
                    station.code(), event.id(), switchTime);
            if (latestLevel != null) {
                if (latestLevel.compareTo(station.safeLowLevel()) < 0) {
                    throw ApiException.unprocessable("SAFE_LOW_VIOLATION")
                            .raise("泵站 " + station.code() + " 水位 " + latestLevel
                                    + " 低于安全下限 " + station.safeLowLevel() + "，强排不予放宽，零切换");
                }
                if (latestLevel.compareTo(station.targetLevel()) <= 0) {
                    throw ApiException.unprocessable("TARGET_REACHED")
                            .raise("泵站 " + station.code() + " 水位 " + latestLevel
                                    + " 已达预降目标 " + station.targetLevel() + "，强排不予放宽，零切换");
                }
            }
            plans.add(new TaskPlan(station, "START", targetByStation.get(station.code()),
                    "TIDE_FORCED_START"));
        }
        return plans;
    }

    /** 退出强排：运行中的泵站排定停泵计划（未运行站不写任务），占用校验同样适用。 */
    private List<TaskPlan> planExitForced(PumpdownEvent event, List<PumpStation> stations,
                                          LocalDateTime switchTime) {
        List<TaskPlan> plans = new ArrayList<>();
        for (PumpStation station : stations) {
            PumpTaskRepository.LastTask last = taskRepository.latestTask(station.code(), event.id());
            if (last == null || !last.running()) {
                continue;
            }
            if (taskRepository.taskExistsAt(station.code(), switchTime)) {
                throw ApiException.unprocessable("STATION_OCCUPIED")
                        .raise("泵站 " + station.code() + " 在 " + switchTime + " 已有启停任务占用，零切换");
            }
            plans.add(new TaskPlan(station, "STOP", null, "TIDE_FORCED_EXIT"));
        }
        return plans;
    }

    // ---------- 查询 ----------

    /** 分区潮位查询。 */
    public TideReadingListResult listReadings(String zoneCode) {
        requireControl(zoneCode);
        return new TideReadingListResult(zoneCode, tideRepository.listByZone(zoneCode));
    }

    /** 候选确认查询：候选状态、固定目标与已收回执。 */
    public CandidateDetailResult candidateDetail(Long candidateId) {
        SwitchCandidate candidate = requireCandidate(candidateId);
        List<CandidateDetailResult.GateTargetItem> targets = switchRepository
                .listTargets(candidateId).stream()
                .map(t -> new CandidateDetailResult.GateTargetItem(t.stationCode(), t.targetOpening()))
                .toList();
        List<CandidateDetailResult.GateReceiptItem> receipts = switchRepository
                .listReceipts(candidateId).stream()
                .map(r -> new CandidateDetailResult.GateReceiptItem(r.stationCode(), r.opening(),
                        r.receivedAt()))
                .toList();
        return new CandidateDetailResult(candidate.id(), candidate.zoneCode(), candidate.direction(),
                candidate.tideTime(), candidate.tideLevel(), candidate.pinnedVersion(),
                candidate.status(), targets, receipts, candidate.createdAt());
    }

    /** 分区工况轨迹查询。 */
    public ModeTrajectoryResult trajectory(String zoneCode) {
        TideControl control = requireControl(zoneCode);
        List<ModeTrajectoryResult.SwitchItem> switches = switchRepository.listTrajectory(zoneCode)
                .stream()
                .map(t -> new ModeTrajectoryResult.SwitchItem(t.candidateId(), t.fromMode(),
                        t.toMode(), t.zoneVersion(), t.tideTime(), t.switchTime()))
                .toList();
        return new ModeTrajectoryResult(zoneCode, control.mode(), control.version(), switches);
    }

    // ---------- 私有辅助 ----------

    private TideControl requireControl(String zoneCode) {
        return controlRepository.find(zoneCode)
                .orElseThrow(() -> ApiException.notFound("TIDE_CONTROL_NOT_FOUND")
                        .raise("分区 " + zoneCode + " 未配置潮汐控制"));
    }

    private SwitchCandidate requireCandidate(Long candidateId) {
        return switchRepository.findCandidate(candidateId)
                .orElseThrow(() -> ApiException.notFound("CANDIDATE_NOT_FOUND")
                        .raise("切换候选不存在: " + candidateId));
    }

    private ApiException versionMismatch(String zoneCode, int expectedVersion) {
        int current = controlRepository.find(zoneCode)
                .map(TideControl::version)
                .orElse(-1);
        return ApiException.versionConflict("VERSION_CONFLICT", current,
                "分区 " + zoneCode + " 工况版本失配：期望 " + expectedVersion + "，拒绝变更");
    }

    /** 切换任务计划：单站动作与联动闸门开度（STOP 无开度）。 */
    private record TaskPlan(PumpStation station, String action, BigDecimal gateOpening,
                            String reason) {
    }
}
