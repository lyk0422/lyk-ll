package com.example.starter.water;

import com.example.starter.common.ApiException;
import com.example.starter.config.WaterQualityProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 传感器生命周期：心跳、恰满 10 分钟失联标记并挂起自动处置、复连人工确认补算。
 */
@Service
public class SensorService {

    private final WaterRepository repository;
    private final WaterQualityService waterQualityService;
    private final WaterQualityProperties properties;
    private final Clock clock;

    public SensorService(WaterRepository repository,
                         WaterQualityService waterQualityService,
                         WaterQualityProperties properties,
                         Clock clock) {
        this.repository = repository;
        this.waterQualityService = waterQualityService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 心跳受理结果。
     */
    public record HeartbeatResult(long tankId, SensorStatus sensorStatus, Instant lastSeenAt) {
    }

    /**
     * 复连人工确认结果；backfilledOrderId 为补算下发的处置指令（无补算需求时为空）。
     */
    public record ReconnectResult(long tankId, SensorStatus sensorStatus, Long backfilledOrderId) {
    }

    /**
     * 传感器心跳：失联池复连后进入待人工确认状态，须确认补算后才恢复自动处置。
     */
    @Transactional
    public HeartbeatResult heartbeat(long tankId) {
        TankRow tank = repository.lockTank(tankId)
                .orElseThrow(() -> ApiException.notFound("展示池不存在: " + tankId));
        Instant now = clock.instant();
        SensorStatus status = tank.sensorStatus() == SensorStatus.ONLINE
                ? SensorStatus.ONLINE
                : SensorStatus.AWAITING_CONFIRM;
        TankRow updated = new TankRow(tank.id(), tank.code(), tank.name(), tank.status(),
                tank.displaySuspended(), tank.autoDisposalSuspended(), tank.dataMissing(),
                tank.consecutiveViolations(), status, now);
        repository.updateTankState(updated);
        return new HeartbeatResult(tankId, status, now);
    }

    /**
     * 复连人工确认：恢复在线与自动处置，保留原告警链，并对失联期间积压的紧急状态补算处置指令。
     */
    @Transactional
    public ReconnectResult reconnectConfirm(long tankId, String operator) {
        TankRow tank = repository.lockTank(tankId)
                .orElseThrow(() -> ApiException.notFound("展示池不存在: " + tankId));
        if (tank.sensorStatus() != SensorStatus.AWAITING_CONFIRM) {
            throw ApiException.conflict("传感器当前状态无需人工确认: " + tank.sensorStatus());
        }
        Instant now = clock.instant();
        TankRow updated = new TankRow(tank.id(), tank.code(), tank.name(), tank.status(),
                tank.displaySuspended(), false, false, tank.consecutiveViolations(),
                SensorStatus.ONLINE, tank.sensorLastSeenAt());
        repository.updateTankState(updated);

        Long backfilledOrderId = null;
        if (tank.status() == TankStatus.EMERGENCY && !repository.hasPendingOrder(tankId)) {
            List<AlarmRow> activeAlarms = repository.findActiveAlarms(tankId);
            long alarmId = activeAlarms.isEmpty()
                    ? repository.insertAlarm(tankId, AlarmLevel.EMERGENCY, "sensor_reconnect",
                            "传感器复连人工确认补算处置", now)
                    : activeAlarms.get(activeAlarms.size() - 1).id();
            List<String> metrics = activeAlarms.isEmpty()
                    ? List.of(ThresholdEvaluator.METRIC_TEMPERATURE)
                    : List.of(activeAlarms.get(activeAlarms.size() - 1).metric().split(","));
            backfilledOrderId = waterQualityService.issueDisposalOrder(alarmId, tankId, metrics, now);
        }
        return new ReconnectResult(tankId, SensorStatus.ONLINE, backfilledOrderId);
    }

    /**
     * 巡检传感器失联：恰满失联时长无心跳即标记数据缺失并挂起自动处置，返回新失联的展示池 ID。
     */
    @Transactional
    public List<Long> sweepSensorLoss() {
        Instant cutoff = clock.instant().minus(Duration.ofMinutes(properties.getSensorLostMinutes()));
        List<Long> lostTankIds = new ArrayList<>();
        for (TankRow candidate : repository.findSensorLostCandidates(cutoff)) {
            TankRow tank = repository.lockTank(candidate.id()).orElseThrow();
            TankRow updated = new TankRow(tank.id(), tank.code(), tank.name(), tank.status(),
                    tank.displaySuspended(), true, true, tank.consecutiveViolations(),
                    SensorStatus.LOST, tank.sensorLastSeenAt());
            repository.updateTankState(updated);
            lostTankIds.add(tank.id());
        }
        return lostTankIds;
    }
}
