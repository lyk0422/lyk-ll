package com.example.starter.greenhouse.service;

import com.example.starter.greenhouse.domain.CommandType;
import com.example.starter.greenhouse.domain.EvaluationStatus;
import com.example.starter.greenhouse.dto.EvaluationResponse;
import com.example.starter.greenhouse.po.ClimateStrategy;
import com.example.starter.greenhouse.po.DailyEvaluation;
import com.example.starter.greenhouse.po.SensorReading;
import com.example.starter.greenhouse.po.Zone;
import com.example.starter.greenhouse.repo.DailyEvaluationRepository;
import com.example.starter.greenhouse.repo.EnergyCommandRepository;
import com.example.starter.greenhouse.repo.LateReadingEventRepository;
import com.example.starter.greenhouse.repo.SensorReadingRepository;
import com.example.starter.greenhouse.repo.StrategyRepository;
import com.example.starter.greenhouse.repo.ZoneRepository;
import com.example.starter.greenhouse.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 日评估服务（UTC 日界）：
 * OPEN 结果可反复复算；封存为终态，重复封存幂等返回首次封存结果；
 * 封存后迟到读数不进入统计，仅登记迟到事件。
 */
@Service
public class EvaluationService {

    private final DailyEvaluationRepository evaluationRepository;
    private final SensorReadingRepository readingRepository;
    private final EnergyCommandRepository commandRepository;
    private final LateReadingEventRepository lateEventRepository;
    private final StrategyRepository strategyRepository;
    private final ZoneRepository zoneRepository;
    private final ClimateBandEvaluator bandEvaluator;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EvaluationService(DailyEvaluationRepository evaluationRepository,
                             SensorReadingRepository readingRepository,
                             EnergyCommandRepository commandRepository,
                             LateReadingEventRepository lateEventRepository,
                             StrategyRepository strategyRepository,
                             ZoneRepository zoneRepository,
                             ClimateBandEvaluator bandEvaluator,
                             ObjectMapper objectMapper,
                             Clock clock) {
        this.evaluationRepository = evaluationRepository;
        this.readingRepository = readingRepository;
        this.commandRepository = commandRepository;
        this.lateEventRepository = lateEventRepository;
        this.strategyRepository = strategyRepository;
        this.zoneRepository = zoneRepository;
        this.bandEvaluator = bandEvaluator;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 查询评估：已封存返回封存快照，未封存按当前数据实时复算（不落库）。 */
    @Transactional(readOnly = true)
    public EvaluationResponse get(String zoneCode, String dayText) {
        Zone zone = mustZone(zoneCode);
        LocalDate day = parseDay(dayText);
        DailyEvaluation stored = evaluationRepository.findByZoneAndDay(zone.getId(), day).orElse(null);
        if (stored != null && stored.getStatus() == EvaluationStatus.SEALED) {
            return toResponse(stored, zone.getZoneCode(), readDetails(stored));
        }
        return compute(zone, day, stored);
    }

    /**
     * 封存：终态。重复封存幂等返回首次结果。
     * 并发封存时依赖 (zone_id, day) 唯一约束与 OPEN→SEALED 的 CAS 保证唯一结果。
     */
    @Transactional
    public EvaluationResponse seal(String zoneCode, String dayText) {
        Zone zone = mustZone(zoneCode);
        LocalDate day = LocalDate.parse(dayText);
        DailyEvaluation evaluation = evaluationRepository.findByZoneAndDay(zone.getId(), day).orElse(null);
        if (evaluation != null && evaluation.getStatus() == EvaluationStatus.SEALED) {
            return toResponse(evaluation, zone.getZoneCode(), readDetails(evaluation));
        }

        Computed computed = doCompute(zone, day);
        Instant now = Instant.now(clock);
        if (evaluation == null) {
            DailyEvaluation row = newRow(zone.getId(), day, computed, EvaluationStatus.SEALED);
            row.setSealedAt(now);
            try {
                evaluationRepository.insert(row);
                return toResponse(row, zone.getZoneCode(), computed.details());
            } catch (DuplicateKeyException e) {
                DailyEvaluation winner = evaluationRepository.findByZoneAndDay(zone.getId(), day).orElseThrow();
                return toResponse(winner, zone.getZoneCode(), readDetails(winner));
            }
        }

        applyComputed(evaluation, computed);
        // 先以 CAS 抢占封存权（行锁串行化并发封存），抢占失败方不得覆盖胜方快照。
        boolean sealed = evaluationRepository.sealIfOpen(evaluation.getId(), now);
        if (!sealed) {
            DailyEvaluation winner = evaluationRepository.findByZoneAndDay(zone.getId(), day).orElseThrow();
            return toResponse(winner, zone.getZoneCode(), readDetails(winner));
        }
        evaluation.setStatus(EvaluationStatus.SEALED);
        evaluation.setSealedAt(now);
        evaluationRepository.updateOpen(evaluation);
        DailyEvaluation finalRow = evaluationRepository.findByZoneAndDay(zone.getId(), day).orElseThrow();
        return toResponse(finalRow, zone.getZoneCode(), computed.details());
    }

    private EvaluationResponse compute(Zone zone, LocalDate day, DailyEvaluation stored) {
        Computed computed = doCompute(zone, day);
        DailyEvaluation transientRow = stored == null
                ? newRow(zone.getId(), day, computed, EvaluationStatus.OPEN)
                : stored;
        if (stored != null) {
            applyComputed(transientRow, computed);
        }
        return toResponse(transientRow, zone.getZoneCode(), computed.details());
    }

    private Computed doCompute(Zone zone, LocalDate day) {
        Instant from = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<SensorReading> readings = readingRepository.findRange(zone.getId(), from, to, false);

        int achieved = 0;
        List<EvaluationResponse.DetailItem> details = new ArrayList<>();
        for (SensorReading r : readings) {
            boolean within = strategyRepository.findEffectiveStrategyIdAt(zone.getId(), r.getSampleTime())
                    .map(id -> strategyRepository.findById(id).orElse(null))
                    .map(s -> bandEvaluator.isWithinBand(s, r))
                    .orElse(false);
            if (within) {
                achieved++;
            }
            details.add(new EvaluationResponse.DetailItem(r.getSampleTime(), r.getTemperature(),
                    r.getHumidity(), bandEvaluator.isDaytime(r.getSampleTime()), within));
        }
        int total = readings.size();
        BigDecimal rate = total == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(achieved).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);

        BigDecimal heating = commandRepository.sumEnergy(zone.getId(), day, CommandType.HEATING);
        BigDecimal lighting = commandRepository.sumEnergy(zone.getId(), day, CommandType.LIGHTING);
        int ventilationCount = commandRepository.countByType(zone.getId(), day, CommandType.VENTILATION);
        long lateCount = lateEventRepository.countByZoneAndDay(zone.getId(), day);
        return new Computed(total, achieved, rate, heating, lighting, ventilationCount, lateCount, details);
    }

    private record Computed(int total, int achieved, BigDecimal rate, BigDecimal heating,
                            BigDecimal lighting, int ventilationCount, long lateCount,
                            List<EvaluationResponse.DetailItem> details) {
        BigDecimal totalKwh() {
            return heating.add(lighting);
        }
    }

    private DailyEvaluation newRow(Long zoneId, LocalDate day, Computed c, EvaluationStatus status) {
        DailyEvaluation e = new DailyEvaluation();
        e.setZoneId(zoneId);
        e.setBudgetDay(day);
        e.setStatus(status);
        e.setCreatedAt(Instant.now(clock));
        applyComputed(e, c);
        return e;
    }

    private void applyComputed(DailyEvaluation e, Computed c) {
        e.setTotalReadings(c.total());
        e.setAchievedReadings(c.achieved());
        e.setAchievementRate(c.rate());
        e.setHeatingKwh(c.heating());
        e.setLightingKwh(c.lighting());
        e.setTotalKwh(c.totalKwh());
        e.setVentilationCount(c.ventilationCount());
        e.setDetailJson(writeDetails(c.details()));
    }

    private EvaluationResponse toResponse(DailyEvaluation e, String zoneCode,
                                          List<EvaluationResponse.DetailItem> details) {
        long lateCount = lateEventRepository.countByZoneAndDay(e.getZoneId(), e.getBudgetDay());
        return new EvaluationResponse(
                e.getId(), zoneCode, e.getBudgetDay(), e.getStatus().name(),
                e.getTotalReadings(), e.getAchievedReadings(), e.getAchievementRate(),
                e.getTotalKwh(), e.getHeatingKwh(), e.getLightingKwh(),
                e.getVentilationCount(), lateCount, e.getSealedAt(), details);
    }

    private String writeDetails(List<EvaluationResponse.DetailItem> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("评估明细序列化失败", ex);
        }
    }

    private List<EvaluationResponse.DetailItem> readDetails(DailyEvaluation e) {
        if (e.getDetailJson() == null || e.getDetailJson().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(e.getDetailJson(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, EvaluationResponse.DetailItem.class));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("评估明细反序列化失败", ex);
        }
    }

    private Zone mustZone(String zoneCode) {
        return zoneRepository.findByCode(zoneCode)
                .orElseThrow(() -> ApiException.notFound("分区不存在: " + zoneCode));
    }

    private LocalDate parseDay(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("日期格式需为 yyyy-MM-dd(UTC): " + text);
        }
    }
}
