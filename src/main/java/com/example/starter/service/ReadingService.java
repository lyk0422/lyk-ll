package com.example.starter.service;

import com.example.starter.api.dto.ReadingDtos;
import com.example.starter.repo.AlarmRepository;
import com.example.starter.repo.EvaluationRepository;
import com.example.starter.repo.ReadingRepository;
import com.example.starter.repo.StrategyRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 传感器读数服务：
 * <ul>
 *     <li>按“分区＋采样时刻”去重，乱序上报按采样时刻归位；</li>
 *     <li>已封存（UTC 日评估终态）的日期只记录迟到事件；</li>
 *     <li>实际温湿度连续 3 个采样点偏离当前生效策略的设定带，恰在第 3 点触发一次偏差告警。</li>
 * </ul>
 */
@Service
public class ReadingService {

    /** 触发告警所需的连续偏离点数，恰为 3。 */
    static final int DEVIATION_RUN_TO_TRIGGER = 3;
    private static final int SCAN_WINDOW = 64;

    private final ReadingRepository readingRepository;
    private final StrategyRepository strategyRepository;
    private final AlarmRepository alarmRepository;
    private final EvaluationRepository evaluationRepository;
    private final SetupService setupService;
    private final Clock clock;

    public ReadingService(ReadingRepository readingRepository, StrategyRepository strategyRepository,
                          AlarmRepository alarmRepository, EvaluationRepository evaluationRepository,
                          SetupService setupService, Clock clock) {
        this.readingRepository = readingRepository;
        this.strategyRepository = strategyRepository;
        this.alarmRepository = alarmRepository;
        this.evaluationRepository = evaluationRepository;
        this.setupService = setupService;
        this.clock = clock;
    }

    @Transactional
    public ReadingDtos.ReadingResponse ingest(ReadingDtos.ReadingRequest request) {
        setupService.requireZone(request.zoneCode());
        Instant now = clock.instant();
        LocalDate utcDate = request.sampleTime().atZone(ZoneOffset.UTC).toLocalDate();

        // 封存为终态：迟到读数只记迟到事件，不参与评估与告警。
        if (evaluationRepository.isSealed(request.zoneCode(), utcDate)) {
            readingRepository.recordLate(request.zoneCode(), request.sampleTime(),
                    request.temperature(), request.humidity(), now);
            return new ReadingDtos.ReadingResponse(request.zoneCode(), request.sampleTime(),
                    request.temperature(), request.humidity(), false, true, null);
        }

        try {
            readingRepository.insert(request.zoneCode(), request.sampleTime(),
                    request.temperature(), request.humidity(), now);
        } catch (DuplicateKeyException e) {
            // 同分区同时刻重复上报：幂等去重，不重复检测、不重复告警。
            return new ReadingDtos.ReadingResponse(request.zoneCode(), request.sampleTime(),
                    request.temperature(), request.humidity(), true, false, null);
        }

        Long alarmId = detectDeviation(request.zoneCode(), request.sampleTime(), now);
        return new ReadingDtos.ReadingResponse(request.zoneCode(), request.sampleTime(),
                request.temperature(), request.humidity(), false, false, alarmId);
    }

    /**
     * 以该分区最新一个采样点为末端（乱序补点会改变末端），沿采样时刻向前统计在同一策略下
     * 连续偏离设定带的点数；恰在连续点数达到 3 且此前无同起点告警时产生一条告警。
     * 这样乱序上报按时刻归位后重算，触发时刻为本次入库时刻，起点为连续序列首个采样时刻。
     */
    private Long detectDeviation(String zoneCode, Instant ingestedSampleTime, Instant now) {
        // 乱序补点时末端仍是分区最新采样点，因此以全分区最大采样时刻（而非本次入库时刻）取窗。
        Instant latestTime = readingRepository.findLatestSampleTime(zoneCode);
        if (latestTime == null) {
            return null;
        }
        List<ReadingRepository.Reading> recent =
                readingRepository.findRecentUntil(zoneCode, latestTime, SCAN_WINDOW);
        if (recent.isEmpty()) {
            return null;
        }
        ReadingRepository.Reading latest = recent.get(recent.size() - 1);
        StrategyRepository.Strategy strategy =
                strategyRepository.findEffectiveAt(zoneCode, latest.sampleTime()).orElse(null);
        if (strategy == null || !isOutOfBand(strategy, latest)) {
            return null;
        }
        int runLength = 0;
        Instant runStart = null;
        for (int i = recent.size() - 1; i >= 0; i--) {
            ReadingRepository.Reading r = recent.get(i);
            StrategyRepository.Strategy effective =
                    strategyRepository.findEffectiveAt(zoneCode, r.sampleTime()).orElse(null);
            if (effective == null || !effective.id().equals(strategy.id()) || !isOutOfBand(strategy, r)) {
                break;
            }
            runLength++;
            runStart = r.sampleTime();
        }
        if (runLength != DEVIATION_RUN_TO_TRIGGER) {
            return null;
        }
        if (alarmRepository.existsWithStart(zoneCode, strategy.strategyCode(), runStart)) {
            return null;
        }
        return alarmRepository.insert(zoneCode, strategy.strategyCode(), runStart, latest.sampleTime(),
                runLength, now);
    }

    /**
     * 偏离设定带：温度落在白天/夜间任一设定点允许带之外，或湿度超出设定点允许带宽。恰在边界视为带内。
     */
    public static boolean isOutOfBand(StrategyRepository.Strategy s, ReadingRepository.Reading r) {
        BigDecimal temp = r.temperature();
        boolean tempInBand = inTolerance(temp, s.dayTemp(), s.tempTolerance())
                || inTolerance(temp, s.nightTemp(), s.tempTolerance());
        boolean humidityInBand = inTolerance(r.humidity(), s.humiditySetpoint(), s.humidityTolerance());
        return !tempInBand || !humidityInBand;
    }

    private static boolean inTolerance(BigDecimal actual, BigDecimal setpoint, BigDecimal tolerance) {
        return actual.subtract(setpoint).abs().compareTo(tolerance) <= 0;
    }
}
