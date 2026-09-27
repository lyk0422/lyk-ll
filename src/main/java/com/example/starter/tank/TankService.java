package com.example.starter.tank;

import com.example.starter.idempotent.IdempotencyService;
import com.example.starter.idempotent.ReplayResult;
import com.example.starter.station.Station;
import com.example.starter.station.StationRepository;
import com.example.starter.web.BusinessRejectedException;
import com.example.starter.web.ResourceNotFoundException;
import com.example.starter.web.dto.TankRegisterRequest;
import com.example.starter.web.dto.TankRegisterResponse;
import com.example.starter.web.dto.TankStatusResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 储罐登记与查询。
 *
 * <ul>
 *   <li>登记校验：0≤安全储备&lt;容量、0≤初始余额≤容量、千克量最多三位小数，
 *       违反为参数非法（400）；一门站至多绑定一个储罐、储罐编码唯一，
 *       重复绑定为业务拒绝（422）；</li>
 *   <li>登记成功版本从 1 开始，并追加 REGISTER 台账明细；</li>
 *   <li>全程在幂等事务内执行，同键同参重放首次结果，同键异参 409，失败不占键。</li>
 * </ul>
 */
@Service
public class TankService {

    private final StationRepository stationRepository;
    private final TankRepository tankRepository;
    private final RefillEventRepository refillEventRepository;
    private final TankLedgerRepository ledgerRepository;
    private final InjectionPlanRepository planRepository;
    private final IdempotencyService idempotencyService;

    public TankService(StationRepository stationRepository,
                       TankRepository tankRepository,
                       RefillEventRepository refillEventRepository,
                       TankLedgerRepository ledgerRepository,
                       InjectionPlanRepository planRepository,
                       IdempotencyService idempotencyService) {
        this.stationRepository = stationRepository;
        this.tankRepository = tankRepository;
        this.refillEventRepository = refillEventRepository;
        this.ledgerRepository = ledgerRepository;
        this.planRepository = planRepository;
        this.idempotencyService = idempotencyService;
    }

    public TankRegisterResponse register(TankRegisterRequest request) {
        Station station = stationRepository.findByCode(request.stationCode())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "station not found: " + request.stationCode()));
        ReplayResult<TankRegisterResponse> replay = idempotencyService.run(
                request.requestId(), station.id(), "TANK_REGISTER", request, 200,
                () -> doRegister(station, request), TankRegisterResponse.class);
        return replay.result();
    }

    private TankRegisterResponse doRegister(Station station, TankRegisterRequest request) {
        BigDecimal capacity = requireKgScale(request.capacityKg(), "capacityKg");
        BigDecimal safetyReserve = requireKgScale(request.safetyReserveKg(), "safetyReserveKg");
        BigDecimal initialBalance = requireKgScale(request.initialBalanceKg(), "initialBalanceKg");

        // 0≤安全储备<容量；0≤初始余额≤容量
        if (safetyReserve.compareTo(capacity) >= 0) {
            throw new IllegalArgumentException(
                    "safetyReserveKg must be less than capacityKg: " + safetyReserve
                            + " >= " + capacity);
        }
        if (initialBalance.compareTo(capacity) > 0) {
            throw new IllegalArgumentException(
                    "initialBalanceKg must not exceed capacityKg: " + initialBalance
                            + " > " + capacity);
        }
        if (tankRepository.findByStationId(station.id()).isPresent()) {
            throw new BusinessRejectedException(
                    "station already bound to a tank: " + station.stationCode());
        }

        Long tankId;
        try {
            tankId = tankRepository.insert(station.id(), request.tankCode(),
                    capacity, safetyReserve, initialBalance);
        } catch (DuplicateKeyException duplicate) {
            // 并发登记同门站/同编码：唯一约束裁决为业务拒绝
            throw new BusinessRejectedException(
                    "tank already registered for station or tankCode: " + request.tankCode());
        }
        BigDecimal zero = BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);
        ledgerRepository.append(tankId, "REGISTER", initialBalance,
                initialBalance, zero, "REGISTER", tankId, request.requestId());

        return new TankRegisterResponse(tankId, request.tankCode(), station.stationCode(),
                capacity, safetyReserve, initialBalance, zero, 1, false);
    }

    /**
     * 储罐状态查询：余额、预占、可用量、版本与待补液事件数。
     */
    public TankStatusResponse status(String stationCode) {
        Station station = stationRepository.findByCode(stationCode)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "station not found: " + stationCode));
        OdorantTank tank = tankRepository.findByStationId(station.id())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "tank not bound for station: " + stationCode));
        long pending = refillEventRepository.countPendingByTank(tank.id());
        return new TankStatusResponse(tank.id(), tank.tankCode(), station.stationCode(),
                tank.capacityKg(), tank.safetyReserveKg(), tank.balanceKg(), tank.reservedKg(),
                tank.availableKg(), tank.version(), pending);
    }

    /**
     * 台账明细查询：余额、预占、补液与消耗的守恒勾稽依据。
     */
    public List<TankLedgerEntry> ledger(String stationCode) {
        OdorantTank tank = requireTank(stationCode);
        return ledgerRepository.listByTank(tank.id());
    }

    /**
     * 待补液/已关闭补液事件查询。
     */
    public List<RefillEvent> refillEvents(String stationCode) {
        Station station = stationRepository.findByCode(stationCode)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "station not found: " + stationCode));
        return refillEventRepository.listByStation(station.id());
    }

    /**
     * 加注计划查询。
     */
    public List<InjectionPlan> plans(String stationCode) {
        Station station = stationRepository.findByCode(stationCode)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "station not found: " + stationCode));
        return planRepository.listByStation(station.id());
    }

    private OdorantTank requireTank(String stationCode) {
        Station station = stationRepository.findByCode(stationCode)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "station not found: " + stationCode));
        return tankRepository.findByStationId(station.id())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "tank not bound for station: " + stationCode));
    }

    /**
     * 千克量规范化为三位小数；超过三位小数视为参数非法。
     */
    static BigDecimal requireKgScale(BigDecimal value, String field) {
        try {
            return value.setScale(3, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException tooPrecise) {
            throw new IllegalArgumentException(field + " must have at most 3 decimal places: " + value);
        }
    }
}
