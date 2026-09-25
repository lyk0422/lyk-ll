package com.example.starter.odorant;

import com.example.starter.close.DailyCloseRepository;
import com.example.starter.idempotent.IdempotencyService;
import com.example.starter.idempotent.ReplayResult;
import com.example.starter.reading.LateEventRepository;
import com.example.starter.reading.OdorantReadingRepository;
import com.example.starter.station.Station;
import com.example.starter.station.StationRepository;
import com.example.starter.valve.LockOrderRepository;
import com.example.starter.valve.ValveState;
import com.example.starter.valve.ValveStateRepository;
import com.example.starter.web.ResourceNotFoundException;
import com.example.starter.web.dto.OdorantDetectRequest;
import com.example.starter.web.dto.OdorantDetectResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 运行监测：末端加臭浓度检测。
 *
 * <ul>
 *   <li>合格区间为目标浓度（20mg/m³）上下 20% 的<b>闭区间</b>，恰在边界判合格；</li>
 *   <li>合格即把连续越限计数归零；<b>恰连续 2 次</b>越限触发告警并锁定加注阀
 *       （第 1 次仅按比例修正加注速率，不告警），同时生成待双人确认的触发锁阀单；</li>
 *   <li>对应业务日期已封存时，本次数据只落迟到事件，不联动、不改阀门状态；</li>
 *   <li>全程在幂等事务内执行，阀门状态以行锁串行裁决。</li>
 * </ul>
 */
@Service
public class OdorantService {

    private static final int BREACH_LIMIT_TO_LOCK = 2;

    private final StationRepository stationRepository;
    private final ValveStateRepository valveStateRepository;
    private final OdorantReadingRepository readingRepository;
    private final LateEventRepository lateEventRepository;
    private final LockOrderRepository lockOrderRepository;
    private final DailyCloseRepository dailyCloseRepository;
    private final IdempotencyService idempotencyService;

    public OdorantService(StationRepository stationRepository,
                          ValveStateRepository valveStateRepository,
                          OdorantReadingRepository readingRepository,
                          LateEventRepository lateEventRepository,
                          LockOrderRepository lockOrderRepository,
                          DailyCloseRepository dailyCloseRepository,
                          IdempotencyService idempotencyService) {
        this.stationRepository = stationRepository;
        this.valveStateRepository = valveStateRepository;
        this.readingRepository = readingRepository;
        this.lateEventRepository = lateEventRepository;
        this.lockOrderRepository = lockOrderRepository;
        this.dailyCloseRepository = dailyCloseRepository;
        this.idempotencyService = idempotencyService;
    }

    public OdorantDetectResponse detect(OdorantDetectRequest request) {
        Station station = stationRepository.findByCode(request.stationCode())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "station not found: " + request.stationCode()));
        ReplayResult<OdorantDetectResponse> replay = idempotencyService.run(
                request.requestId(), station.id(), "ODORANT_DETECT", request, 200,
                () -> doDetect(station, request), OdorantDetectResponse.class);
        return replay.result();
    }

    private OdorantDetectResponse doDetect(Station station, OdorantDetectRequest request) {
        BigDecimal target = station.targetConcentrationMgM3();
        BigDecimal toleranceAmount = target
                .multiply(station.concentrationTolerancePct())
                .divide(BigDecimal.valueOf(100), 3, RoundingMode.HALF_UP);
        BigDecimal lowerBound = target.subtract(toleranceAmount);
        BigDecimal upperBound = target.add(toleranceAmount);

        BigDecimal measured = request.concentrationMgM3();
        // 闭区间：恰在 16.000 / 24.000 边界均判合格
        boolean qualified = measured.compareTo(lowerBound) >= 0
                && measured.compareTo(upperBound) <= 0;

        LocalDate bizDate = request.detectedAt().toLocalDate();
        if (dailyCloseRepository.isSealed(station.id(), bizDate)) {
            // 封存终态：迟到检测只记事件
            lateEventRepository.insert(station.id(), "ODORANT", request.requestId(),
                    request.detectedAt(), request);
            ValveState state = valveStateRepository.find(station.id());
            return new OdorantDetectResponse(qualified, state.breachCount(),
                    state.injectionLocked(), state.alarmActive(), lowerBound, upperBound, true, false);
        }

        ValveState state = valveStateRepository.selectForUpdate(station.id());
        readingRepository.insert(station.id(), request.requestId(), request.detectedAt(),
                request.gasFlowM3(), measured, qualified);

        if (state.injectionLocked()) {
            // 已锁定待双人复开，后续检测不自动解除
            return new OdorantDetectResponse(qualified, state.breachCount(),
                    true, true, lowerBound, upperBound, false, false);
        }

        if (qualified) {
            int breachCount = 0;
            valveStateRepository.updateState(station.id(), false, false, breachCount,
                    state.currentInjectionRateMgM3(), state.currentPressureMpa());
            return new OdorantDetectResponse(true, breachCount, false, false,
                    lowerBound, upperBound, false, false);
        }

        int newBreachCount = state.breachCount() + 1;
        if (newBreachCount >= BREACH_LIMIT_TO_LOCK) {
            // 恰连续第 2 次越限：告警 + 锁定加注阀，生成待双人确认锁阀单
            valveStateRepository.updateState(station.id(), true, true, newBreachCount,
                    state.currentInjectionRateMgM3(), state.currentPressureMpa());
            lockOrderRepository.insert(station.id(), "LOCK", "PENDING",
                    null, null, request.requestId());
            return new OdorantDetectResponse(false, newBreachCount, true, true,
                    lowerBound, upperBound, false, false);
        }

        // 第 1 次越限：按流量比例投加原则修正加注速率（速率 × 目标浓度/实测浓度）
        BigDecimal correctedRate = state.currentInjectionRateMgM3()
                .multiply(target)
                .divide(measured, 3, RoundingMode.HALF_UP);
        valveStateRepository.updateState(station.id(), false, false, newBreachCount,
                correctedRate, state.currentPressureMpa());
        return new OdorantDetectResponse(false, newBreachCount, false, false,
                lowerBound, upperBound, false, false);
    }
}
