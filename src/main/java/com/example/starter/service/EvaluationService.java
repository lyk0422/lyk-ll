package com.example.starter.service;

import com.example.starter.api.dto.EvaluationDtos;
import com.example.starter.repo.BudgetRepository;
import com.example.starter.repo.CommandRepository;
import com.example.starter.repo.EvaluationRepository;
import com.example.starter.repo.ReadingRepository;
import com.example.starter.repo.StrategyRepository;
import com.example.starter.web.NotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日评估服务（UTC 日界）：
 * <ul>
 *     <li>封存（SEALED）为终态：重复封存幂等返回首次结果，并标记 replayed=true；</li>
 *     <li>封存后到达的读数由读数服务隔离为迟到事件，不影响已封存结果；</li>
 *     <li>输出设定达成率与按指令类型汇总的能耗明细，全部基于读数/指令/预算表现算，可复算。</li>
 * </ul>
 */
@Service
public class EvaluationService {

    private static final String SEALED = "SEALED";

    private final EvaluationRepository evaluationRepository;
    private final ReadingRepository readingRepository;
    private final StrategyRepository strategyRepository;
    private final CommandRepository commandRepository;
    private final BudgetRepository budgetRepository;
    private final SetupService setupService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EvaluationService(EvaluationRepository evaluationRepository, ReadingRepository readingRepository,
                             StrategyRepository strategyRepository, CommandRepository commandRepository,
                             BudgetRepository budgetRepository, SetupService setupService,
                             ObjectMapper objectMapper, Clock clock) {
        this.evaluationRepository = evaluationRepository;
        this.readingRepository = readingRepository;
        this.strategyRepository = strategyRepository;
        this.commandRepository = commandRepository;
        this.budgetRepository = budgetRepository;
        this.setupService = setupService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public EvaluationDtos.EvaluationResponse seal(EvaluationDtos.EvaluationRequest request) {
        setupService.requireZone(request.zoneCode());
        var existing = evaluationRepository.find(request.zoneCode(), request.evalDate());
        if (existing.isPresent()) {
            // 封存终态：重复封存幂等成功，返回首次结果。
            return toResponse(existing.get(), true);
        }
        Computed computed = compute(request.zoneCode(), request.evalDate());
        Instant now = clock.instant();
        EvaluationRepository.Evaluation evaluation = new EvaluationRepository.Evaluation(
                null, request.zoneCode(), request.evalDate(), SEALED,
                computed.inBandCount(), computed.totalCount(), computed.achievementRate(),
                computed.budgetKwh(), computed.consumedKwh(), computed.energyDetailJson(), now, now);
        try {
            evaluationRepository.insert(evaluation);
        } catch (DuplicateKeyException e) {
            // 并发封存：唯一约束保证仅一条成功，败者幂等返回胜者结果。
            EvaluationRepository.Evaluation winner =
                    evaluationRepository.find(request.zoneCode(), request.evalDate()).orElseThrow();
            return toResponse(winner, true);
        }
        return toResponse(
                evaluationRepository.find(request.zoneCode(), request.evalDate()).orElseThrow(), false);
    }

    @Transactional(readOnly = true)
    public EvaluationDtos.EvaluationResponse get(String zoneCode, LocalDate date) {
        return evaluationRepository.find(zoneCode, date)
                .map(e -> toResponse(e, false))
                .orElseThrow(() -> new NotFoundException("当日评估不存在：" + zoneCode + " " + date));
    }

    /**
     * 现算达成率与能耗明细。读数是否在带内，按该采样时刻生效策略的设定带逐点判定；边界视为带内。
     */
    private Computed compute(String zoneCode, LocalDate evalDate) {
        List<ReadingRepository.Reading> readings = readingRepository.findByUtcDate(zoneCode, evalDate);
        int inBand = 0;
        for (ReadingRepository.Reading r : readings) {
            StrategyRepository.Strategy strategy =
                    strategyRepository.findEffectiveAt(zoneCode, r.sampleTime()).orElse(null);
            if (strategy != null && !ReadingService.isOutOfBand(strategy, r)) {
                inBand++;
            }
        }
        int total = readings.size();
        BigDecimal rate = total == 0
                ? BigDecimal.ZERO.setScale(4)
                : BigDecimal.valueOf(inBand).divide(BigDecimal.valueOf(total), 4,
                        java.math.RoundingMode.HALF_UP);

        Map<String, Aggregate> aggregates = new LinkedHashMap<>();
        BigDecimal consumed = BigDecimal.ZERO.setScale(3);
        for (CommandRepository.Command c : commandRepository.findByUtcDate(zoneCode, evalDate)) {
            Aggregate aggregate = aggregates.computeIfAbsent(c.commandType(), k -> new Aggregate());
            aggregate.commandCount++;
            aggregate.energyKwh = aggregate.energyKwh.add(c.energyKwh());
            consumed = consumed.add(c.energyKwh());
        }
        List<EvaluationDtos.EnergyItem> items = new ArrayList<>();
        aggregates.forEach((type, agg) -> items.add(
                new EvaluationDtos.EnergyItem(type, agg.commandCount, agg.energyKwh.setScale(3))));

        BigDecimal budgetKwh = budgetRepository.find(zoneCode, evalDate)
                .map(BudgetRepository.Budget::budgetKwh)
                .orElse(null);
        return new Computed(inBand, total, rate, budgetKwh, consumed.setScale(3), writeJson(items));
    }

    private String writeJson(List<EvaluationDtos.EnergyItem> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("能耗明细序列化失败", e);
        }
    }

    private List<EvaluationDtos.EnergyItem> readJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<EvaluationDtos.EnergyItem>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("能耗明细反序列化失败", e);
        }
    }

    private EvaluationDtos.EvaluationResponse toResponse(EvaluationRepository.Evaluation e, boolean replayed) {
        return new EvaluationDtos.EvaluationResponse(e.id(), e.zoneCode(), e.evalDate(), e.status(),
                e.inBandCount(), e.totalCount(), e.achievementRate(), e.budgetKwh(), e.consumedKwh(),
                readJson(e.energyDetailJson()), e.sealedAt(), replayed);
    }

    private static final class Aggregate {
        private int commandCount;
        private BigDecimal energyKwh = BigDecimal.ZERO.setScale(3);
    }

    private record Computed(int inBandCount, int totalCount, BigDecimal achievementRate,
                            BigDecimal budgetKwh, BigDecimal consumedKwh, String energyDetailJson) {
    }
}
