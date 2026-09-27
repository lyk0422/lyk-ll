package com.example.starter.pump.service;

import com.example.starter.pump.core.TimeProvider;
import com.example.starter.pump.domain.PumpStation;
import com.example.starter.pump.domain.PumpdownEvent;
import com.example.starter.pump.domain.TideSwitchCandidate;
import com.example.starter.pump.domain.TideZoneConfig;
import com.example.starter.pump.error.ApiException;
import com.example.starter.pump.repo.CatalogRepository;
import com.example.starter.pump.repo.LevelReadingRepository;
import com.example.starter.pump.repo.PumpTaskRepository;
import com.example.starter.pump.repo.PumpdownEventRepository;
import com.example.starter.pump.repo.TideConfigRepository;
import com.example.starter.pump.repo.TideSwitchRepository;
import com.example.starter.pump.web.dto.TideConfirmRequest;
import com.example.starter.pump.web.dto.TideConfirmResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 强排切换候选确认：在事务内校验旧分区序列版本、所有站无其他占用、
 * 闸门合成到位回执齐全，全部满足才原子启用新序列、终结旧序列并写各站任务；
 * 任一不到位零切换，不允许一半站先启机。
 *
 * <p>切换不放宽既有联锁：10分钟启机间隔、预降目标停泵与安全下限照常校验。
 */
@Service
public class TideSwitchService {

    /** 相邻两次启机的最小间隔（与手动启泵一致，不因强排放宽）。 */
    static final Duration MIN_START_INTERVAL = Duration.ofMinutes(10);

    private final TideConfigRepository configRepository;
    private final TideSwitchRepository switchRepository;
    private final PumpdownEventRepository eventRepository;
    private final CatalogRepository catalogRepository;
    private final PumpTaskRepository taskRepository;
    private final LevelReadingRepository levelRepository;
    private final TimeProvider timeProvider;

    public TideSwitchService(TideConfigRepository configRepository,
                             TideSwitchRepository switchRepository,
                             PumpdownEventRepository eventRepository,
                             CatalogRepository catalogRepository,
                             PumpTaskRepository taskRepository,
                             LevelReadingRepository levelRepository,
                             TimeProvider timeProvider) {
        this.configRepository = configRepository;
        this.switchRepository = switchRepository;
        this.eventRepository = eventRepository;
        this.catalogRepository = catalogRepository;
        this.taskRepository = taskRepository;
        this.levelRepository = levelRepository;
        this.timeProvider = timeProvider;
    }

    /** 候选确认（幂等首次执行体）：全部校验通过才原子切换。 */
    public TideConfirmResult confirm(TideConfirmRequest request) {
        TideSwitchCandidate candidate = switchRepository.findCandidate(request.candidateId())
                .orElseThrow(() -> ApiException.notFound("CANDIDATE_NOT_FOUND")
                        .raise("切换候选不存在: " + request.candidateId()));
        TideZoneConfig config = configRepository.findForUpdate(candidate.zoneCode())
                .orElseThrow(() -> ApiException.unprocessable("TIDE_NOT_CONFIGURED")
                        .raise("分区 " + candidate.zoneCode() + " 未配置潮汐控制"));
        if (!candidate.pending()) {
            throw ApiException.unprocessable("CANDIDATE_NOT_PENDING")
                    .raise("候选 " + candidate.id() + " 状态为 " + candidate.status()
                            + "，不可重复确认；旧序列回执只归旧任务，不改变新任务状态");
        }
        TideService.requireVersion(request.expectedVersion(), config);

        PumpdownEvent event = eventRepository.findActiveByZone(candidate.zoneCode())
                .orElseThrow(() -> ApiException.unprocessable("NO_ACTIVE_PUMPDOWN")
                        .raise("分区 " + candidate.zoneCode() + " 无进行中的预降事件，无法切换"));

        List<TideSwitchRepository.StationTarget> targets =
                switchRepository.loadCandidateStations(candidate.id());
        Map<String, BigDecimal> receipts = validateReceipts(candidate, targets, request);

        boolean enter = "FORCED".equals(candidate.targetMode());
        List<PumpStation> stations = new ArrayList<>();
        for (TideSwitchRepository.StationTarget target : targets) {
            PumpStation station = catalogRepository.findStation(target.stationCode())
                    .orElseThrow(() -> ApiException.unprocessable("STATION_NOT_FOUND")
                            .raise("泵站不存在: " + target.stationCode()));
            checkStationFree(station, event, request.confirmTime(), enter);
            stations.add(station);
        }

        // 全部校验通过：原子启用新序列、终结旧序列、写各站任务
        taskRepository.terminateActiveSequences(event.id(), candidate.zoneCode(),
                request.confirmTime());
        Long sequenceId;
        try {
            sequenceId = taskRepository.insertSequence(event.id(), candidate.zoneCode(),
                    request.confirmTime(), timeProvider.now());
        } catch (DuplicateKeyException e) {
            throw ApiException.concurrentConflict("ZONE_SEQUENCE_CONFLICT",
                    "分区 " + candidate.zoneCode() + " 在 " + request.confirmTime()
                            + " 已存在序列，切换零受理");
        }
        String action = enter ? "START" : "STOP";
        String reason = enter ? "TIDE_FORCED_ENTER" : "TIDE_FORCED_EXIT";
        List<Long> taskIds = new ArrayList<>();
        for (PumpStation station : stations) {
            Long taskId = taskRepository.insertTask(sequenceId, event.id(), station.code(),
                    action, request.confirmTime(), reason);
            if (taskId == null) {
                throw ApiException.concurrentConflict("STATION_TIME_CONFLICT",
                        "泵站 " + station.code() + " 在 " + request.confirmTime()
                                + " 已存在启停任务，切换零受理");
            }
            if (enter) {
                // 闸门到位回执与启泵任务同事务提交
                taskRepository.insertGateLog(taskId, station.code(),
                        receipts.get(station.code()), request.confirmTime());
            }
            taskIds.add(taskId);
        }

        int newVersion = config.version() + 1;
        switchRepository.updateStatus(candidate.id(), "ACTIVATED", request.confirmTime());
        configRepository.switchMode(candidate.zoneCode(), candidate.targetMode(), newVersion,
                timeProvider.now());
        switchRepository.insertTrail(candidate.zoneCode(), config.currentMode(),
                candidate.targetMode(), newVersion, candidate.id(), request.requestId(),
                request.confirmTime());

        return new TideConfirmResult(candidate.id(), candidate.zoneCode(), sequenceId,
                candidate.targetMode(), newVersion, request.confirmTime(), taskIds, false);
    }

    /** 闸门合成到位回执须与候选固定目标一一吻合：缺站、多站或开度不符均零切换。 */
    private Map<String, BigDecimal> validateReceipts(TideSwitchCandidate candidate,
                                                     List<TideSwitchRepository.StationTarget> targets,
                                                     TideConfirmRequest request) {
        Map<String, BigDecimal> receipts = new HashMap<>();
        for (TideConfirmRequest.GateReceipt receipt : request.gateReceipts()) {
            receipts.put(receipt.stationCode(), receipt.opening());
        }
        if (receipts.size() != request.gateReceipts().size() || receipts.size() != targets.size()) {
            throw ApiException.unprocessable("GATE_RECEIPT_INCOMPLETE")
                    .raise("闸门回执数量与候选站目标不一致，零切换");
        }
        for (TideSwitchRepository.StationTarget target : targets) {
            BigDecimal opening = receipts.get(target.stationCode());
            if (opening == null) {
                throw ApiException.unprocessable("GATE_RECEIPT_INCOMPLETE")
                        .raise("泵站 " + target.stationCode() + " 闸门到位回执缺失，零切换");
            }
            if (opening.compareTo(target.targetOpening()) != 0) {
                throw ApiException.unprocessable("GATE_RECEIPT_INCOMPLETE")
                        .raise("泵站 " + target.stationCode() + " 闸门到位开度 " + opening
                                + " 与候选目标 " + target.targetOpening() + " 不符，零切换");
            }
        }
        return receipts;
    }

    /** 单站占用与联锁校验：无其他占用，且10分钟间隔、目标停泵、安全下限不放宽。 */
    private void checkStationFree(PumpStation station, PumpdownEvent event,
                                  LocalDateTime confirmTime, boolean enter) {
        if (taskRepository.taskExistsAt(station.code(), confirmTime)) {
            throw ApiException.unprocessable("STATION_OCCUPIED")
                    .raise("泵站 " + station.code() + " 在 " + confirmTime + " 已有启停任务，零切换");
        }
        PumpTaskRepository.LastTask last = taskRepository.latestTask(station.code(), event.id());
        if (enter) {
            if (last != null && last.running()) {
                throw ApiException.unprocessable("STATION_OCCUPIED")
                        .raise("泵站 " + station.code() + " 已在运行被占用，零切换");
            }
            LocalDateTime latestStart = taskRepository.latestStartTimeBefore(station.code(),
                    confirmTime);
            if (latestStart != null
                    && Duration.between(latestStart, confirmTime).compareTo(MIN_START_INTERVAL) < 0) {
                throw ApiException.unprocessable("START_INTERVAL_TOO_SHORT")
                        .raise("泵站 " + station.code() + " 距上次启泵不足10分钟，强排不放宽，零切换");
            }
            BigDecimal level = levelRepository.latestLevel(station.code(), event.id());
            if (level != null) {
                if (level.compareTo(station.safeLowLevel()) < 0) {
                    throw ApiException.unprocessable("SAFE_LOW_BELOW")
                            .raise("泵站 " + station.code() + " 水位低于安全下限，强排不放宽，零切换");
                }
                if (level.compareTo(station.targetLevel()) <= 0) {
                    throw ApiException.unprocessable("TARGET_REACHED")
                            .raise("泵站 " + station.code() + " 已达预降目标位，强排不放宽，零切换");
                }
            }
        } else {
            if (last == null || !last.running()) {
                throw ApiException.unprocessable("PUMP_NOT_RUNNING")
                        .raise("泵站 " + station.code() + " 当前未运行，无法退出强排，零切换");
            }
        }
    }
}
