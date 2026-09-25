package com.example.starter.greenhouse.service;

import com.example.starter.greenhouse.domain.AlertStatus;
import com.example.starter.greenhouse.dto.ReadingIngestRequest;
import com.example.starter.greenhouse.dto.ReadingResponse;
import com.example.starter.greenhouse.po.ClimateStrategy;
import com.example.starter.greenhouse.po.DeviationAlert;
import com.example.starter.greenhouse.po.LateReadingEvent;
import com.example.starter.greenhouse.po.SensorReading;
import com.example.starter.greenhouse.po.Zone;
import com.example.starter.greenhouse.repo.DailyEvaluationRepository;
import com.example.starter.greenhouse.repo.DeviationAlertRepository;
import com.example.starter.greenhouse.repo.LateReadingEventRepository;
import com.example.starter.greenhouse.repo.SensorReadingRepository;
import com.example.starter.greenhouse.repo.StrategyRepository;
import com.example.starter.greenhouse.repo.ZoneRepository;
import com.example.starter.greenhouse.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 传感器读数上报：分区+采样时刻去重、乱序按时刻归位、连续 3 点偏离触发告警、
 * 日评估封存后迟到读数仅登记迟到事件。
 */
@Service
public class ReadingService {

    private final ZoneRepository zoneRepository;
    private final SensorReadingRepository readingRepository;
    private final StrategyRepository strategyRepository;
    private final DeviationAlertRepository alertRepository;
    private final DailyEvaluationRepository evaluationRepository;
    private final LateReadingEventRepository lateEventRepository;
    private final ClimateBandEvaluator bandEvaluator;
    private final Clock clock;

    public ReadingService(ZoneRepository zoneRepository,
                          SensorReadingRepository readingRepository,
                          StrategyRepository strategyRepository,
                          DeviationAlertRepository alertRepository,
                          DailyEvaluationRepository evaluationRepository,
                          LateReadingEventRepository lateEventRepository,
                          ClimateBandEvaluator bandEvaluator,
                          Clock clock) {
        this.zoneRepository = zoneRepository;
        this.readingRepository = readingRepository;
        this.strategyRepository = strategyRepository;
        this.alertRepository = alertRepository;
        this.evaluationRepository = evaluationRepository;
        this.lateEventRepository = lateEventRepository;
        this.bandEvaluator = bandEvaluator;
        this.clock = clock;
    }

    @Transactional
    public ReadingResponse ingest(ReadingIngestRequest req) {
        Zone zone = zoneRepository.findByCode(req.zoneCode())
                .orElseThrow(() -> ApiException.notFound("分区不存在: " + req.zoneCode()));
        Instant sampleTime = parseInstant(req.sampleTime());

        // 去重：同分区+采样时刻已存在则直接返回首次结果，不重复处理。
        Optional<SensorReading> existed = readingRepository.findByZoneAndTime(zone.getId(), sampleTime);
        if (existed.isPresent()) {
            return new ReadingResponse(existed.get().getId(), zone.getZoneCode(), sampleTime,
                    true, existed.get().isLateFlag(), false, null);
        }

        Instant now = Instant.now(clock);
        LocalDate day = LocalDate.ofInstant(sampleTime, ZoneOffset.UTC);
        boolean sealed = evaluationRepository.findByZoneAndDay(zone.getId(), day)
                .map(e -> e.getStatus() == com.example.starter.greenhouse.domain.EvaluationStatus.SEALED)
                .orElse(false);

        SensorReading reading = new SensorReading();
        reading.setZoneId(zone.getId());
        reading.setSampleTime(sampleTime);
        reading.setTemperature(req.temperature());
        reading.setHumidity(req.humidity());
        reading.setLateFlag(sealed);
        reading.setIngestedAt(now);
        try {
            readingRepository.insert(reading);
        } catch (DuplicateKeyException e) {
            SensorReading existing = readingRepository.findByZoneAndTime(zone.getId(), sampleTime).orElseThrow();
            return new ReadingResponse(existing.getId(), zone.getZoneCode(), sampleTime,
                    true, existing.isLateFlag(), false, null);
        }

        if (sealed) {
            LateReadingEvent event = new LateReadingEvent();
            event.setZoneId(zone.getId());
            event.setReadingId(reading.getId());
            event.setSampleTime(sampleTime);
            event.setBudgetDay(day);
            event.setCreatedAt(now);
            lateEventRepository.insert(event);
            return new ReadingResponse(reading.getId(), zone.getZoneCode(), sampleTime, false, true, false, null);
        }

        Long newAlertId = evaluateAlerts(zone.getId());
        return new ReadingResponse(reading.getId(), zone.getZoneCode(), sampleTime,
                false, false, newAlertId != null, newAlertId);
    }

    /**
     * 按采样时刻升序扫描全量有效读数，补算“连续 3 点偏离”：
     * 偏离段中恰第 3 点触发一次告警（以触发采样时刻去重，乱序归位结果幂等）；
     * 最新读数回到带内时关闭未恢复告警。
     * 返回本次扫描新建的首个告警 id（即本次上报所触发的告警）。
     */
    private Long evaluateAlerts(Long zoneId) {
        List<SensorReading> readings = readingRepository.findRange(
                zoneId, Instant.ofEpochMilli(0), Instant.parse("9999-12-31T23:59:59Z"), false);

        Long triggeredId = null;
        int consecutive = 0;
        Instant firstDeviateTime = null;
        for (SensorReading r : readings) {
            if (deviates(r)) {
                if (consecutive == 0) {
                    firstDeviateTime = r.getSampleTime();
                }
                consecutive++;
                if (consecutive == 3) {
                    // 乱序补点时，时间序第 3 点可能早于本次上报时刻；
                    // 只要本次扫描新建了告警，即视为本次上报所触发。
                    Long newId = fireAlertIfAbsent(zoneId, r, firstDeviateTime);
                    if (newId != null && triggeredId == null) {
                        triggeredId = newId;
                    }
                }
            } else {
                consecutive = 0;
                firstDeviateTime = null;
            }
        }

        if (!readings.isEmpty()) {
            SensorReading latest = readings.get(readings.size() - 1);
            if (!deviates(latest)) {
                alertRepository.findOpenByZone(zoneId).ifPresent(open ->
                        alertRepository.markRecovered(open.getId(), Instant.now(clock)));
            }
        }
        return triggeredId;
    }

    private Long fireAlertIfAbsent(Long zoneId, SensorReading third, Instant firstDeviateTime) {
        if (alertRepository.findByZoneAndTriggerTime(zoneId, third.getSampleTime()).isPresent()) {
            return null;
        }
        Long strategyId = strategyRepository.findEffectiveStrategyIdAt(zoneId, third.getSampleTime())
                .orElse(null);
        if (strategyId == null) {
            return null;
        }
        DeviationAlert alert = new DeviationAlert();
        alert.setZoneId(zoneId);
        alert.setStrategyId(strategyId);
        alert.setStatus(AlertStatus.OPEN);
        alert.setFirstDeviateTime(firstDeviateTime);
        alert.setTriggerSampleTime(third.getSampleTime());
        alert.setCreatedAt(Instant.now(clock));
        alertRepository.insert(alert);
        return alert.getId();
    }

    /** 该读数相对其采样时刻生效策略的设定带是否偏离；无生效策略视为不偏离。 */
    private boolean deviates(SensorReading r) {
        Long strategyId = strategyRepository.findEffectiveStrategyIdAt(r.getZoneId(), r.getSampleTime())
                .orElse(null);
        if (strategyId == null) {
            return false;
        }
        ClimateStrategy s = strategyRepository.findById(strategyId).orElse(null);
        return s != null && !bandEvaluator.isWithinBand(s, r);
    }

    static Instant parseInstant(String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("时间格式需为 UTC ISO-8601，如 2026-09-25T01:00:00Z: " + text);
        }
    }
}
