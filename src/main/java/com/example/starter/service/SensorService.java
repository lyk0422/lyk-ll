package com.example.starter.service;

import com.example.starter.domain.EventType;
import com.example.starter.domain.SensorState;
import com.example.starter.domain.Thresholds;
import com.example.starter.domain.WaterSample;
import com.example.starter.domain.ZonePool;
import com.example.starter.repo.EventRepository;
import com.example.starter.repo.SampleRepository;
import com.example.starter.repo.ZoneRepository;
import com.example.starter.web.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 传感器在线状态服务：恰满 10 分钟失联标记数据缺失并挂起自动处置，复连须人工确认补算。
 */
@Service
public class SensorService {

    private final ZoneRepository zoneRepository;
    private final SampleRepository sampleRepository;
    private final EventRepository eventRepository;
    private final MonitoringService monitoringService;

    public SensorService(ZoneRepository zoneRepository, SampleRepository sampleRepository,
                         EventRepository eventRepository, MonitoringService monitoringService) {
        this.zoneRepository = zoneRepository;
        this.sampleRepository = sampleRepository;
        this.eventRepository = eventRepository;
        this.monitoringService = monitoringService;
    }

    /**
     * 人工确认复连结果。
     *
     * @param backfilledSamples 补算的采样条数
     */
    public record ReconnectResult(String zoneCode, SensorState sensorState, int backfilledSamples) {
    }

    /**
     * 传感器心跳。失联状态下收到心跳转为待人工确认；在线状态仅刷新心跳时间。
     */
    @Transactional
    public SensorState heartbeat(String zoneCode, LocalDateTime at) {
        ZonePool zone = zoneRepository.findByCode(zoneCode)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "展示池不存在: " + zoneCode));
        SensorState state = zone.sensorState();
        if (state == SensorState.MISSING) {
            zoneRepository.updateSensor(zone.id(), SensorState.PENDING_CONFIRM, at, zone.missingSince(), at);
            eventRepository.insert(zone.id(), EventType.SENSOR_RECONNECTED, null, null,
                    "传感器复连，待人工确认补算", at);
            return SensorState.PENDING_CONFIRM;
        }
        zoneRepository.updateSensor(zone.id(), state, at, zone.missingSince(), at);
        return state;
    }

    /**
     * 扫描恰满 10 分钟未心跳的在线传感器，标记数据缺失并挂起自动处置。返回新标记条数。
     */
    @Transactional
    public int checkLoss(LocalDateTime now) {
        int marked = 0;
        for (ZonePool zone : zoneRepository.listAll()) {
            if (zone.sensorState() != SensorState.ONLINE || zone.lastHeartbeatAt() == null) {
                continue;
            }
            LocalDateTime lostAt = zone.lastHeartbeatAt().plus(Thresholds.SENSOR_LOST_AFTER);
            if (!lostAt.isAfter(now)) {
                zoneRepository.updateSensor(zone.id(), SensorState.MISSING, zone.lastHeartbeatAt(),
                        lostAt, now);
                eventRepository.insert(zone.id(), EventType.SENSOR_MISSING, null, null,
                        "恰满 10 分钟未心跳，标记数据缺失并挂起自动处置", now);
                marked++;
            }
        }
        return marked;
    }

    /**
     * 人工确认复连：恢复在线后按采样时间顺序补算缺失期间的采样，保留原告警链。
     */
    @Transactional
    public ReconnectResult confirmReconnect(String zoneCode, LocalDateTime now) {
        ZonePool zone = zoneRepository.findByCode(zoneCode)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "展示池不存在: " + zoneCode));
        if (zone.sensorState() != SensorState.PENDING_CONFIRM) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "传感器状态为 " + zone.sensorState() + "，不允许人工确认复连");
        }
        zoneRepository.updateSensor(zone.id(), SensorState.ONLINE, zone.lastHeartbeatAt(), null, now);
        eventRepository.insert(zone.id(), EventType.SENSOR_CONFIRMED, null, null,
                "人工确认复连，开始补算", now);
        ZonePool current = zoneRepository.findById(zone.id()).orElseThrow();
        List<WaterSample> pending = sampleRepository.findUnevaluated(zone.id());
        for (WaterSample sample : pending) {
            current = monitoringService.evaluate(current, sample,
                    Thresholds.violatedParams(sample.dissolvedOxygen(), sample.ammoniaNitrogen(),
                            sample.temperature()));
            sampleRepository.markEvaluated(sample.id());
        }
        return new ReconnectResult(zoneCode, SensorState.ONLINE, pending.size());
    }
}
