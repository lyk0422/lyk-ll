package com.example.starter.pump.service;

import com.example.starter.pump.domain.PumpStation;
import com.example.starter.pump.domain.PumpdownEvent;
import com.example.starter.pump.error.ApiException;
import com.example.starter.pump.repo.CatalogRepository;
import com.example.starter.pump.repo.PumpTaskRepository;
import com.example.starter.pump.repo.PumpdownEventRepository;
import com.example.starter.pump.web.dto.PumpCommandRequest;
import com.example.starter.pump.web.dto.PumpCommandResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 泵站启停：启机间隔不少于10分钟（恰10分钟合法）、分区同时刻唯一序列、
 * 同站同时刻唯一任务，启泵与闸门开度同事务提交。
 */
@Service
public class PumpCommandService {

    /** 相邻两次启机的最小间隔。 */
    static final Duration MIN_START_INTERVAL = Duration.ofMinutes(10);

    private final PumpTaskRepository taskRepository;
    private final PumpdownEventRepository eventRepository;
    private final CatalogRepository catalogRepository;

    public PumpCommandService(PumpTaskRepository taskRepository,
                              PumpdownEventRepository eventRepository,
                              CatalogRepository catalogRepository) {
        this.taskRepository = taskRepository;
        this.eventRepository = eventRepository;
        this.catalogRepository = catalogRepository;
    }

    /** 处理手动启停指令（幂等首次执行体）。 */
    public PumpCommandResult processManual(PumpCommandRequest request) {
        PumpdownEvent event = requireActiveEvent(request.pumpdownId());
        PumpStation station = requireStationInZone(request.stationCode(), event.zoneCode());
        return switch (request.action()) {
            case "START" -> start(station, event, request.taskTime(),
                    request.gateOpening(), "MANUAL_START");
            case "STOP" -> stop(station, event, request.taskTime(), "MANUAL_STOP");
            default -> throw ApiException.unprocessable("UNKNOWN_ACTION")
                    .raise("未知启停动作: " + request.action() + "（仅支持 START/STOP）");
        };
    }

    /**
     * 液位复测触发的自动停泵（恰达目标位或低于安全下限），不走 requestId 幂等。
     *
     * @return 停泵任务ID；泵本已停止返回 null
     */
    public Long autoStop(PumpStation station, PumpdownEvent event, LocalDateTime taskTime,
                         String reason) {
        PumpTaskRepository.LastTask last = taskRepository.latestTask(station.code(), event.id());
        if (last == null || !last.running()) {
            return null;
        }
        Long sequenceId = taskRepository.findOrCreateSequence(event.id(), station.zoneCode(),
                taskTime, taskTime);
        Long taskId = taskRepository.insertTask(sequenceId, event.id(), station.code(), "STOP",
                taskTime, reason);
        return taskId;
    }

    private PumpCommandResult start(PumpStation station, PumpdownEvent event,
                                    LocalDateTime taskTime, BigDecimal requestedOpening,
                                    String reason) {
        PumpTaskRepository.LastTask last = taskRepository.latestTask(station.code(), event.id());
        if (last != null && last.running()) {
            throw ApiException.unprocessable("PUMP_ALREADY_RUNNING")
                    .raise("泵站 " + station.code() + " 已在运行，禁止重复启泵");
        }
        if (last != null) {
            LocalDateTime latestStart = taskRepository.latestStartTimeBefore(station.code(), taskTime);
            if (latestStart != null
                    && Duration.between(latestStart, taskTime).compareTo(MIN_START_INTERVAL) < 0) {
                throw ApiException.unprocessable("START_INTERVAL_TOO_SHORT")
                        .raise("泵站 " + station.code() + " 距上次启泵不足10分钟，防水锤拒绝启泵");
            }
        }

        BigDecimal opening = requestedOpening != null ? requestedOpening : station.gateStartOpening();
        if (opening.compareTo(BigDecimal.ZERO) < 0 || opening.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw ApiException.unprocessable("INVALID_GATE_OPENING")
                    .raise("闸门开度必须在0-100之间: " + opening);
        }

        Long sequenceId;
        Long taskId;
        try {
            sequenceId = taskRepository.findOrCreateSequence(event.id(), station.zoneCode(),
                    taskTime, taskTime);
            taskId = taskRepository.insertTask(sequenceId, event.id(), station.code(), "START",
                    taskTime, reason);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.concurrentConflict("ZONE_SEQUENCE_CONFLICT",
                    "分区 " + station.zoneCode() + " 在 " + taskTime + " 的联动序列正被并发创建，唯一受理");
        }
        if (taskId == null) {
            throw ApiException.concurrentConflict("STATION_TIME_CONFLICT",
                    "泵站 " + station.code() + " 在 " + taskTime + " 已存在启停任务，并发指令唯一受理");
        }
        // 与启泵任务同一事务：闸门未到位则整体回滚，绝不允许开泵而闸门未联动
        taskRepository.insertGateLog(taskId, station.code(), opening, taskTime);

        return new PumpCommandResult(taskId, sequenceId, event.id(), station.code(), "START",
                taskTime, opening, reason, false);
    }

    private PumpCommandResult stop(PumpStation station, PumpdownEvent event,
                                   LocalDateTime taskTime, String reason) {
        PumpTaskRepository.LastTask last = taskRepository.latestTask(station.code(), event.id());
        if (last == null || !last.running()) {
            throw ApiException.unprocessable("PUMP_NOT_RUNNING")
                    .raise("泵站 " + station.code() + " 当前未运行，无需停泵");
        }
        Long sequenceId = taskRepository.findOrCreateSequence(event.id(), station.zoneCode(),
                taskTime, taskTime);
        Long taskId = taskRepository.insertTask(sequenceId, event.id(), station.code(), "STOP",
                taskTime, reason);
        if (taskId == null) {
            throw ApiException.concurrentConflict("STATION_TIME_CONFLICT",
                    "泵站 " + station.code() + " 在 " + taskTime + " 已存在启停任务，并发指令唯一受理");
        }
        return new PumpCommandResult(taskId, sequenceId, event.id(), station.code(), "STOP",
                taskTime, null, reason, false);
    }

    private PumpdownEvent requireActiveEvent(Long pumpdownId) {
        PumpdownEvent event = eventRepository.findById(pumpdownId)
                .orElseThrow(() -> ApiException.unprocessable("PUMPDOWN_NOT_FOUND")
                        .raise("预降事件不存在: " + pumpdownId));
        if (!event.active()) {
            throw ApiException.unprocessable("PUMPDOWN_ARCHIVED")
                    .raise("预降事件 " + pumpdownId + " 已归档终态，不可再启停");
        }
        return event;
    }

    private PumpStation requireStationInZone(String stationCode, String zoneCode) {
        PumpStation station = catalogRepository.findStation(stationCode)
                .orElseThrow(() -> ApiException.unprocessable("STATION_NOT_FOUND")
                        .raise("泵站不存在: " + stationCode));
        if (!station.zoneCode().equals(zoneCode)) {
            throw ApiException.unprocessable("STATION_ZONE_MISMATCH")
                    .raise("泵站 " + stationCode + " 不属于预降分区 " + zoneCode);
        }
        return station;
    }
}
