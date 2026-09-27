package com.example.starter.tank;

import com.example.starter.idempotent.IdempotencyService;
import com.example.starter.idempotent.ReplayResult;
import com.example.starter.station.Station;
import com.example.starter.station.StationRepository;
import com.example.starter.web.BusinessRejectException;
import com.example.starter.web.ResourceNotFoundException;
import com.example.starter.web.VersionConflictException;
import com.example.starter.web.dto.InjectionCancelRequest;
import com.example.starter.web.dto.InjectionCancelResponse;
import com.example.starter.web.dto.InjectionIssueRequest;
import com.example.starter.web.dto.InjectionIssueResponse;
import com.example.starter.web.dto.InjectionReceiptRequest;
import com.example.starter.web.dto.InjectionReceiptResponse;
import com.example.starter.web.dto.ReplenishRequest;
import com.example.starter.web.dto.ReplenishResponse;
import com.example.starter.web.dto.TankLedgerItem;
import com.example.starter.web.dto.TankRegisterRequest;
import com.example.starter.web.dto.TankRegisterResponse;
import com.example.starter.web.dto.TankStatusResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 储罐交接台账服务：登记、受控加注（预占/回执/取消）、补液原子入账与查询。
 *
 * <p>计量约定（全部以 kg、三位小数、{@link BigDecimal#compareTo} 按规范值比较）：
 * <ul>
 *   <li>预占只增 reserved，余额不变；消耗同时减余额与预占；取消/释放只减预占；补液只增余额；</li>
 *   <li>预占后可用量（余额−预占）不得低于安全储备，恰等允许；低于则只生成待补液事件，
 *       不下发加注、版本不变；</li>
 *   <li>每次成功写操作储罐版本加 1，失败回滚不占幂等键；</li>
 *   <li>补液入账仅关闭“可满足”的待补液事件，不自动重发任何旧加注命令。</li>
 * </ul>
 */
@Service
public class TankService {

    /** 千克三位小数。 */
    public static final int SCALE = 3;
    /** 合成验收浓度许可闭区间下限 mg/m3（恰界允许）。 */
    public static final BigDecimal MIN_CONCENTRATION = new BigDecimal("19.000");
    /** 合成验收浓度许可闭区间上限 mg/m3。 */
    public static final BigDecimal MAX_CONCENTRATION = new BigDecimal("21.000");

    private final StationRepository stationRepository;
    private final TankRepository tankRepository;
    private final TankLedgerRepository ledgerRepository;
    private final InjectionPlanRepository planRepository;
    private final ReplenishmentOrderRepository replenishmentRepository;
    private final PendingReplenishEventRepository pendingRepository;
    private final IdempotencyService idempotencyService;

    public TankService(StationRepository stationRepository,
                       TankRepository tankRepository,
                       TankLedgerRepository ledgerRepository,
                       InjectionPlanRepository planRepository,
                       ReplenishmentOrderRepository replenishmentRepository,
                       PendingReplenishEventRepository pendingRepository,
                       IdempotencyService idempotencyService) {
        this.stationRepository = stationRepository;
        this.tankRepository = tankRepository;
        this.ledgerRepository = ledgerRepository;
        this.planRepository = planRepository;
        this.replenishmentRepository = replenishmentRepository;
        this.pendingRepository = pendingRepository;
        this.idempotencyService = idempotencyService;
    }

    private static BigDecimal kg(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private Station requireStation(String stationCode) {
        return stationRepository.findByCode(stationCode)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "station not found: " + stationCode));
    }

    private Tank requireTankForUpdate(Station station) {
        return tankRepository.findByStationIdForUpdate(station.id())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "tank not bound for station: " + station.stationCode()));
    }

    private static void requireVersion(Tank tank, Integer expectedVersion) {
        if (!tank.version().equals(expectedVersion)) {
            throw new VersionConflictException(
                    "expectedVersion=" + expectedVersion + " but current version=" + tank.version());
        }
    }

    // ===== 登记 =====

    public TankRegisterResponse register(TankRegisterRequest request) {
        Station station = requireStation(request.stationCode());
        ReplayResult<TankRegisterResponse> replay = idempotencyService.run(
                request.requestId(), station.id(), "TANK_REGISTER", request, 200,
                () -> doRegister(station, request), TankRegisterResponse.class);
        return replay.result();
    }

    private TankRegisterResponse doRegister(Station station, TankRegisterRequest request) {
        if (tankRepository.findByStationId(station.id()).isPresent()) {
            throw new BusinessRejectException("station already bound to a tank: " + station.stationCode());
        }
        BigDecimal capacity = kg(request.capacityKg());
        BigDecimal safety = kg(request.safetyReserveKg());
        BigDecimal initial = kg(request.initialBalanceKg());
        if (capacity.signum() <= 0) {
            throw new IllegalArgumentException("容量必须为正");
        }
        if (safety.compareTo(capacity) >= 0) {
            throw new IllegalArgumentException("安全储备必须满足 0≤安全储备<容量");
        }
        if (initial.compareTo(capacity) > 0) {
            throw new IllegalArgumentException("期初余额不得大于容量");
        }

        Long tankId;
        try {
            tankId = tankRepository.insert(station.id(), request.tankCode(), capacity, safety, initial);
        } catch (DuplicateKeyException duplicate) {
            throw new BusinessRejectException("储罐编码或门站绑定重复: " + request.tankCode());
        }
        if (initial.signum() > 0) {
            ledgerRepository.append(station.id(), tankId, request.requestId(), "REGISTER",
                    initial, initial, BigDecimal.ZERO.setScale(SCALE), request.registeredAt());
        }
        return new TankRegisterResponse(tankId, station.stationCode(), request.tankCode(),
                capacity, safety, initial, BigDecimal.ZERO.setScale(SCALE), initial, 1, false);
    }

    // ===== 受控加注：下达（预占） =====

    public InjectionIssueResponse issue(InjectionIssueRequest request) {
        Station station = requireStation(request.stationCode());
        ReplayResult<InjectionIssueResponse> replay = idempotencyService.run(
                request.requestId(), station.id(), "INJECTION_ISSUE", request, 200,
                () -> doIssue(station, request), InjectionIssueResponse.class);
        return replay.result();
    }

    private InjectionIssueResponse doIssue(Station station, InjectionIssueRequest request) {
        Tank tank = requireTankForUpdate(station);
        requireVersion(tank, request.expectedVersion());
        BigDecimal required = kg(request.requiredKg());
        if (required.signum() <= 0) {
            throw new IllegalArgumentException("预占量必须为正");
        }

        BigDecimal availableAfter = tank.availableKg().subtract(required);
        if (availableAfter.compareTo(tank.safetyReserveKg()) < 0) {
            // 低于安全储备：生成待补液事件，加注不下发，余额/预占/版本均不变
            pendingRepository.insertOpen(station.id(), tank.id(), request.requestId(),
                    "MANUAL", required);
            return new InjectionIssueResponse(null, station.stationCode(), false, required,
                    tank.balanceKg(), tank.reservedKg(), tank.availableKg(), tank.safetyReserveKg(),
                    true, tank.version(), false);
        }

        BigDecimal newReserved = tank.reservedKg().add(required);
        Long planId = planRepository.insertIssued(station.id(), tank.id(), request.requestId(),
                "MANUAL", request.issuedAt(), required);
        tankRepository.updateQuantities(tank.id(), tank.balanceKg(), newReserved);
        ledgerRepository.append(station.id(), tank.id(), request.requestId(), "RESERVE",
                required, tank.balanceKg(), newReserved, request.issuedAt());
        return new InjectionIssueResponse(planId, station.stationCode(), true, required,
                tank.balanceKg(), newReserved, tank.balanceKg().subtract(newReserved),
                tank.safetyReserveKg(), false, tank.version() + 1, false);
    }

    // ===== 受控加注：执行回执 =====

    public InjectionReceiptResponse receipt(InjectionReceiptRequest request) {
        Station station = requireStation(request.stationCode());
        ReplayResult<InjectionReceiptResponse> replay = idempotencyService.run(
                request.requestId(), station.id(), "INJECTION_RECEIPT", request, 200,
                () -> doReceipt(station, request), InjectionReceiptResponse.class);
        return replay.result();
    }

    private InjectionReceiptResponse doReceipt(Station station, InjectionReceiptRequest request) {
        Tank tank = requireTankForUpdate(station);
        requireVersion(tank, request.expectedVersion());
        InjectionPlan plan = planRepository.findByRequestIdForUpdate(request.planRequestId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "injection plan not found: " + request.planRequestId()));
        if (!plan.stationId().equals(station.id()) || !plan.tankId().equals(tank.id())) {
            throw new ResourceNotFoundException("injection plan not found for station: " + request.planRequestId());
        }
        if ("EXECUTED".equals(plan.status())) {
            throw new BusinessRejectException("加注计划已回执，重复回执不得重复扣量: " + request.planRequestId());
        }
        if ("CANCELLED".equals(plan.status())) {
            throw new BusinessRejectException("加注计划已取消，不能回执: " + request.planRequestId());
        }

        BigDecimal actual = kg(request.actualKg());
        if (actual.compareTo(plan.requiredKg()) > 0) {
            throw new BusinessRejectException(
                    "实际消耗量 " + actual + "kg 不得大于预占量 " + plan.requiredKg() + "kg");
        }
        BigDecimal released = plan.requiredKg().subtract(actual);

        int updated = planRepository.markExecuted(plan.id(), request.requestId(), actual,
                released, request.receiptAt());
        if (updated != 1) {
            // 与取消并发：状态机条件更新落败，整体回滚不扣量
            throw new BusinessRejectException("加注计划并发结算冲突: " + request.planRequestId());
        }

        BigDecimal newBalance = tank.balanceKg().subtract(actual);
        BigDecimal newReserved = tank.reservedKg().subtract(plan.requiredKg());
        tankRepository.updateQuantities(tank.id(), newBalance, newReserved);
        if (actual.signum() > 0) {
            ledgerRepository.append(station.id(), tank.id(), request.requestId(), "CONSUME",
                    actual, newBalance, tank.reservedKg().subtract(actual), request.receiptAt());
        }
        if (released.signum() > 0) {
            ledgerRepository.append(station.id(), tank.id(), request.requestId(), "RELEASE",
                    released, newBalance, newReserved, request.receiptAt());
        }
        return new InjectionReceiptResponse(plan.id(), station.stationCode(), plan.requiredKg(),
                actual, released, newBalance, newReserved, newBalance.subtract(newReserved),
                tank.version() + 1, false);
    }

    // ===== 受控加注：取消 =====

    public InjectionCancelResponse cancel(InjectionCancelRequest request) {
        Station station = requireStation(request.stationCode());
        ReplayResult<InjectionCancelResponse> replay = idempotencyService.run(
                request.requestId(), station.id(), "INJECTION_CANCEL", request, 200,
                () -> doCancel(station, request), InjectionCancelResponse.class);
        return replay.result();
    }

    private InjectionCancelResponse doCancel(Station station, InjectionCancelRequest request) {
        Tank tank = requireTankForUpdate(station);
        requireVersion(tank, request.expectedVersion());
        InjectionPlan plan = planRepository.findByRequestIdForUpdate(request.planRequestId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "injection plan not found: " + request.planRequestId()));
        if (!plan.stationId().equals(station.id()) || !plan.tankId().equals(tank.id())) {
            throw new ResourceNotFoundException("injection plan not found for station: " + request.planRequestId());
        }
        if ("CANCELLED".equals(plan.status())) {
            throw new BusinessRejectException("加注计划已取消，不得重复取消: " + request.planRequestId());
        }
        if ("EXECUTED".equals(plan.status())) {
            throw new BusinessRejectException("加注计划已回执，不能取消: " + request.planRequestId());
        }

        BigDecimal released = plan.requiredKg();
        int updated = planRepository.markCancelled(plan.id(), request.requestId(), released,
                request.cancelledAt());
        if (updated != 1) {
            throw new BusinessRejectException("加注计划并发结算冲突: " + request.planRequestId());
        }

        BigDecimal newReserved = tank.reservedKg().subtract(released);
        tankRepository.updateQuantities(tank.id(), tank.balanceKg(), newReserved);
        ledgerRepository.append(station.id(), tank.id(), request.requestId(), "RELEASE",
                released, tank.balanceKg(), newReserved, request.cancelledAt());
        return new InjectionCancelResponse(plan.id(), station.stationCode(), plan.requiredKg(),
                released, tank.balanceKg(), newReserved, tank.balanceKg().subtract(newReserved),
                tank.version() + 1, false);
    }

    // ===== 补液入账 =====

    public ReplenishResponse replenish(ReplenishRequest request) {
        Station station = requireStation(request.stationCode());
        ReplayResult<ReplenishResponse> replay = idempotencyService.run(
                request.requestId(), station.id(), "TANK_REPLENISH", request, 200,
                () -> doReplenish(station, request), ReplenishResponse.class);
        return replay.result();
    }

    private ReplenishResponse doReplenish(Station station, ReplenishRequest request) {
        Tank tank = requireTankForUpdate(station);
        requireVersion(tank, request.expectedVersion());
        BigDecimal quantity = kg(request.quantityKg());
        BigDecimal concentration = kg(request.acceptedConcentrationMgM3());
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("补液数量必须为正");
        }
        if (request.firstHandler().equals(request.secondHandler())) {
            throw new BusinessRejectException("补液须两名不同交接人共同确认");
        }
        if (concentration.compareTo(MIN_CONCENTRATION) < 0
                || concentration.compareTo(MAX_CONCENTRATION) > 0) {
            throw new BusinessRejectException(
                    "验收浓度须在许可闭区间[" + MIN_CONCENTRATION + "," + MAX_CONCENTRATION + "]mg/m3");
        }
        BigDecimal newBalance = tank.balanceKg().add(quantity);
        if (newBalance.compareTo(tank.capacityKg()) > 0) {
            throw new BusinessRejectException(
                    "补液后余额 " + newBalance + "kg 超过容量 " + tank.capacityKg() + "kg");
        }
        if (replenishmentRepository.existsByBatchNo(request.transportBatchNo())) {
            throw new BusinessRejectException("运输批号已存在: " + request.transportBatchNo());
        }

        Long orderId;
        try {
            orderId = replenishmentRepository.insert(new ReplenishmentOrder(
                    null, station.id(), tank.id(), request.requestId(),
                    request.transportBatchNo(), quantity, concentration,
                    request.firstHandler(), request.secondHandler(),
                    tank.balanceKg(), newBalance, request.deliveredAt()));
        } catch (DuplicateKeyException duplicate) {
            throw new BusinessRejectException("运输批号或请求号重复: " + request.transportBatchNo());
        }
        tankRepository.updateQuantities(tank.id(), newBalance, tank.reservedKg());
        ledgerRepository.append(station.id(), tank.id(), request.requestId(), "REPLENISH",
                quantity, newBalance, tank.reservedKg(), request.deliveredAt());

        // 仅关闭加入后“可满足”的开放待补液事件；关闭不等于重发旧加注命令
        BigDecimal availableAfter = newBalance.subtract(tank.reservedKg());
        List<String> closedRequests = new ArrayList<>();
        for (PendingReplenishEvent event : pendingRepository.findOpenByTankForUpdate(tank.id())) {
            if (event.requiredKg().compareTo(availableAfter) <= 0) {
                int rows = pendingRepository.close(event.id(), orderId);
                if (rows == 1) {
                    closedRequests.add(event.sourceRequestId());
                }
            }
        }

        return new ReplenishResponse(orderId, station.stationCode(), request.transportBatchNo(),
                quantity, concentration, tank.balanceKg(), newBalance, availableAfter,
                closedRequests, tank.version() + 1, false);
    }

    // ===== 查询 =====

    public TankStatusResponse status(String stationCode) {
        Station station = requireStation(stationCode);
        Tank tank = tankRepository.findByStationId(station.id())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "tank not bound for station: " + station.stationCode()));
        List<TankLedgerItem> items = ledgerRepository.findByTank(tank.id()).stream()
                .map(e -> new TankLedgerItem(e.entryType(), e.requestId(), e.amountKg(),
                        e.balanceAfterKg(), e.reservedAfterKg(), e.bizTime()))
                .toList();
        return new TankStatusResponse(tank.id(), station.stationCode(), tank.tankCode(),
                tank.capacityKg(), tank.safetyReserveKg(), tank.balanceKg(), tank.reservedKg(),
                tank.availableKg(), tank.version(), items);
    }

    // ===== 压力联动事务内预占 =====

    /**
     * 在压力联动事务内调用：未绑定储罐返回 notBound（旧行为不变）；绑定储罐则按剂量预占。
     * 压力联动在同事务同步完成，故预占立即转实际消耗（未用释放为 0），
     * 被安全储备拦截时只生成待补液事件、不加注。
     *
     * @param doseKg 比例加注剂量 kg（三位小数）
     */
    public PressureReservationOutcome reserveForPressure(Station station, String requestId,
                                                         LocalDateTime bizTime,
                                                         BigDecimal doseKg) {
        Tank tank = tankRepository.findByStationIdForUpdate(station.id()).orElse(null);
        if (tank == null) {
            return PressureReservationOutcome.notBound();
        }
        BigDecimal dose = kg(doseKg);
        if (dose.signum() <= 0) {
            return new PressureReservationOutcome(true, null,
                    BigDecimal.ZERO.setScale(SCALE), false, tank.version());
        }

        BigDecimal availableAfter = tank.availableKg().subtract(dose);
        if (availableAfter.compareTo(tank.safetyReserveKg()) < 0) {
            // 预占后可用量将低于安全储备：只生成待补液事件，不下发加注
            pendingRepository.insertOpen(station.id(), tank.id(), requestId, "PRESSURE", dose);
            return new PressureReservationOutcome(false, null,
                    BigDecimal.ZERO.setScale(SCALE), true, tank.version());
        }

        BigDecimal reservedAfterReserve = tank.reservedKg().add(dose);
        Long planId = planRepository.insertIssued(station.id(), tank.id(), requestId,
                "PRESSURE", bizTime, dose);
        // 联动同事务完成：预占立即转消耗，未用部分为 0
        BigDecimal released = BigDecimal.ZERO.setScale(SCALE);
        int settled = planRepository.markExecuted(planId, requestId, dose, released, bizTime);
        if (settled != 1) {
            throw new IllegalStateException("pressure plan auto-settle failed: " + planId);
        }
        BigDecimal newBalance = tank.balanceKg().subtract(dose);
        tankRepository.updateQuantities(tank.id(), newBalance, tank.reservedKg());
        ledgerRepository.append(station.id(), tank.id(), requestId, "RESERVE",
                dose, tank.balanceKg(), reservedAfterReserve, bizTime);
        ledgerRepository.append(station.id(), tank.id(), requestId, "CONSUME",
                dose, newBalance, tank.reservedKg(), bizTime);
        return new PressureReservationOutcome(true, planId, dose, false, tank.version() + 1);
    }
}
