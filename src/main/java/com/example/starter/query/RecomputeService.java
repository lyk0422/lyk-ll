package com.example.starter.query;

import com.example.starter.close.DailyCloseRepository;
import com.example.starter.close.DailyCloseRow;
import com.example.starter.station.Station;
import com.example.starter.station.StationRepository;
import com.example.starter.web.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * 可复算服务：浓度曲线、压力链与消耗勾稽均可由原始数据重新计算核验。
 *
 * <p>复算仅依赖原始读数、门站限值与联动记录，重新执行闭区间判定与勾稽公式，
 * 不读取首次处理时缓存的判定结论。
 */
@Service
public class RecomputeService {

    private static final BigDecimal ALLOWED_DEVIATION_PCT = new BigDecimal("3.0000");

    private final StationRepository stationRepository;
    private final RecomputeRepository recomputeRepository;
    private final DailyCloseRepository dailyCloseRepository;

    public RecomputeService(StationRepository stationRepository,
                            RecomputeRepository recomputeRepository,
                            DailyCloseRepository dailyCloseRepository) {
        this.stationRepository = stationRepository;
        this.recomputeRepository = recomputeRepository;
        this.dailyCloseRepository = dailyCloseRepository;
    }

    /**
     * 复算指定业务日期的浓度曲线（含闭区间边界重新判定）。
     */
    @Transactional(readOnly = true)
    public List<CurvePoint> concentrationCurve(String stationCode, LocalDate bizDate) {
        Station station = loadStation(stationCode);
        BigDecimal[] bounds = concentrationBounds(station);
        return recomputeRepository.findOdorantReadings(station.id(), bizDate).stream()
                .map(r -> new CurvePoint(
                        r.requestId(), r.detectedAt(), r.gasFlowM3(), r.concentrationMgM3(),
                        withinBounds(r.concentrationMgM3(), bounds[0], bounds[1]),
                        bounds[0], bounds[1]))
                .toList();
    }

    /**
     * 复算指定业务日期的压力链：重新分级并回查每条联动的调压/加注指令。
     */
    @Transactional(readOnly = true)
    public List<PressureChainPoint> pressureChain(String stationCode, LocalDate bizDate) {
        Station station = loadStation(stationCode);
        return recomputeRepository.findPressureReadings(station.id(), bizDate).stream()
                .map(r -> new PressureChainPoint(
                        r.requestId(), r.detectedAt(), r.pressureMpa(),
                        grade(station, r.pressureMpa()),
                        r.taskId(), r.taskStatus(),
                        r.pressureAdjusted(), r.injectionAdjusted(),
                        r.pressureAfterMpa(), r.injectionDoseMg()))
                .toList();
    }

    /**
     * 复算消耗勾稽：累计流量 → 理论消耗 → 偏差，与已封存日结相互印证。
     */
    @Transactional(readOnly = true)
    public ReconciliationResult reconcile(String stationCode, LocalDate bizDate) {
        Station station = loadStation(stationCode);
        BigDecimal totalFlow = recomputeRepository.sumOdorantFlow(station.id(), bizDate);
        int readingCount = recomputeRepository.countOdorantReadings(station.id(), bizDate);
        BigDecimal expected = totalFlow.multiply(station.targetConcentrationMgM3())
                .setScale(3, RoundingMode.HALF_UP);
        DailyCloseRow closeRow = dailyCloseRepository.find(station.id(), bizDate).orElse(null);
        BigDecimal reported = closeRow == null ? null : closeRow.reportedOdorantMg();
        BigDecimal deviation;
        if (reported == null) {
            deviation = null;
        } else if (expected.compareTo(BigDecimal.ZERO) == 0) {
            deviation = reported.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                    : new BigDecimal("100.0000");
        } else {
            deviation = reported.subtract(expected).abs()
                    .multiply(BigDecimal.valueOf(100))
                    .divide(expected, 4, RoundingMode.HALF_UP);
        }
        return new ReconciliationResult(bizDate, totalFlow, expected, reported, deviation,
                closeRow != null && "SEALED".equals(closeRow.status()), readingCount);
    }

    private Station loadStation(String stationCode) {
        return stationRepository.findByCode(stationCode)
                .orElseThrow(() -> new ResourceNotFoundException("station not found: " + stationCode));
    }

    private BigDecimal[] concentrationBounds(Station station) {
        BigDecimal target = station.targetConcentrationMgM3();
        BigDecimal amount = target.multiply(station.concentrationTolerancePct())
                .divide(BigDecimal.valueOf(100), 3, RoundingMode.HALF_UP);
        return new BigDecimal[]{target.subtract(amount), target.add(amount)};
    }

    private boolean withinBounds(BigDecimal value, BigDecimal lower, BigDecimal upper) {
        // 闭区间：恰在边界合格
        return value.compareTo(lower) >= 0 && value.compareTo(upper) <= 0;
    }

    private String grade(Station station, BigDecimal measured) {
        if (measured.compareTo(station.pressureLowLimitMpa()) < 0) {
            return "LOW";
        }
        if (measured.compareTo(station.pressureHighLimitMpa()) > 0) {
            return "HIGH";
        }
        return "NORMAL";
    }
}
