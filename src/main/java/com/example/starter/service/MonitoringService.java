package com.example.starter.service;

import com.example.starter.domain.Alarm;
import com.example.starter.domain.EventType;
import com.example.starter.domain.SensorState;
import com.example.starter.domain.Thresholds;
import com.example.starter.domain.WaterSample;
import com.example.starter.domain.ZonePool;
import com.example.starter.domain.ZoneStatus;
import com.example.starter.repo.AlarmRepository;
import com.example.starter.repo.EventRepository;
import com.example.starter.repo.SampleRepository;
import com.example.starter.repo.ZoneRepository;
import com.example.starter.web.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 水质监测服务：采样接入、越限判定、连续 3 点升级紧急并联动处置。
 */
@Service
public class MonitoringService {

    private final ZoneRepository zoneRepository;
    private final SampleRepository sampleRepository;
    private final AlarmRepository alarmRepository;
    private final EventRepository eventRepository;
    private final DisposalService disposalService;

    public MonitoringService(ZoneRepository zoneRepository, SampleRepository sampleRepository,
                             AlarmRepository alarmRepository, EventRepository eventRepository,
                             DisposalService disposalService) {
        this.zoneRepository = zoneRepository;
        this.sampleRepository = sampleRepository;
        this.alarmRepository = alarmRepository;
        this.eventRepository = eventRepository;
        this.disposalService = disposalService;
    }

    /**
     * 采样接入结果。
     *
     * @param evaluated 是否已实时评估（传感器失联期间为 false，待人工确认后补算）
     */
    public record SampleResult(long sampleId, List<String> violatedParams, ZoneStatus zoneStatus,
                               int consecutiveViolations, boolean evaluated) {
    }

    /**
     * 接入一条水质采样：任一参数越限即告警，恰等限值正常；连续 3 点越限升级紧急并自动下发处置。
     * 传感器失联期间采样仅登记不评估，待复连人工确认后补算。
     */
    @Transactional
    public SampleResult ingest(String zoneCode, String requestId, BigDecimal dissolvedOxygen,
                               BigDecimal ammoniaNitrogen, BigDecimal temperature,
                               LocalDateTime sampledAt) {
        ZonePool zone = zoneRepository.findByCode(zoneCode)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "展示池不存在: " + zoneCode));
        List<String> violated = Thresholds.violatedParams(dissolvedOxygen, ammoniaNitrogen, temperature);
        boolean evaluateNow = zone.sensorState() == SensorState.ONLINE;
        long sampleId = sampleRepository.insert(zone.id(), dissolvedOxygen, ammoniaNitrogen, temperature,
                !violated.isEmpty(), evaluateNow, sampledAt, requestId);
        ZoneStatus status = zone.status();
        int consecutive = zone.consecutiveViolations();
        if (evaluateNow) {
            WaterSample sample = new WaterSample(sampleId, zone.id(), dissolvedOxygen, ammoniaNitrogen,
                    temperature, !violated.isEmpty(), true, sampledAt, requestId);
            ZonePool updated = evaluate(zone, sample, violated);
            status = updated.status();
            consecutive = updated.consecutiveViolations();
        }
        return new SampleResult(sampleId, violated, status, consecutive, evaluateNow);
    }

    /**
     * 评估单条采样并推进池状态机；实时评估与失联补算共用。返回更新后的池状态。
     */
    public ZonePool evaluate(ZonePool zone, WaterSample sample, List<String> violated) {
        LocalDateTime now = sample.sampledAt();
        if (violated.isEmpty()) {
            ZoneStatus status = zone.status();
            if (status == ZoneStatus.ALARM) {
                for (Alarm alarm : alarmRepository.findUnresolved(zone.id())) {
                    alarmRepository.resolve(alarm.id(), now);
                }
                eventRepository.insert(zone.id(), EventType.STATUS_CHANGE, ZoneStatus.ALARM.name(),
                        ZoneStatus.NORMAL.name(), "采样恢复正常", now);
                status = ZoneStatus.NORMAL;
            }
            ZonePool updated = new ZonePool(zone.id(), zone.code(), zone.name(), status, 0,
                    zone.sensorState(), zone.lastHeartbeatAt(), zone.missingSince(), now);
            zoneRepository.updateState(updated);
            return updated;
        }

        int consecutive = zone.consecutiveViolations() + 1;
        ZoneStatus status = zone.status();
        if (status == ZoneStatus.NORMAL) {
            eventRepository.insert(zone.id(), EventType.STATUS_CHANGE, ZoneStatus.NORMAL.name(),
                    ZoneStatus.ALARM.name(), "采样越限进入告警", now);
            status = ZoneStatus.ALARM;
        }
        String params = String.join(",", violated);
        String detail = "越限参数[" + params + "] 溶解氧=" + sample.dissolvedOxygen()
                + " 氨氮=" + sample.ammoniaNitrogen() + " 温度=" + sample.temperature();
        long alarmId = alarmRepository.insert(zone.id(), params, detail, sample.id(), now);
        eventRepository.insert(zone.id(), EventType.ALARM_RAISED, null, null,
                "alarm#" + alarmId + " " + detail, now);

        if (status == ZoneStatus.ALARM && consecutive >= Thresholds.EMERGENCY_CONSECUTIVE) {
            alarmRepository.escalate(alarmId);
            eventRepository.insert(zone.id(), EventType.STATUS_CHANGE, ZoneStatus.ALARM.name(),
                    ZoneStatus.EMERGENCY.name(), "连续 " + consecutive + " 个采样点越限，升级紧急状态", now);
            status = ZoneStatus.EMERGENCY;
            if (zone.sensorState() == SensorState.ONLINE) {
                disposalService.dispatch(zone.id(), alarmId, violated, now);
                eventRepository.insert(zone.id(), EventType.STATUS_CHANGE, ZoneStatus.EMERGENCY.name(),
                        ZoneStatus.DISPOSING.name(), "自动下发处置指令，进入处置中", now);
                status = ZoneStatus.DISPOSING;
            } else {
                eventRepository.insert(zone.id(), EventType.STATUS_CHANGE, null, null,
                        "传感器非在线，自动处置挂起", now);
            }
        }
        ZonePool updated = new ZonePool(zone.id(), zone.code(), zone.name(), status, consecutive,
                zone.sensorState(), zone.lastHeartbeatAt(), zone.missingSince(), now);
        zoneRepository.updateState(updated);
        return updated;
    }
}
