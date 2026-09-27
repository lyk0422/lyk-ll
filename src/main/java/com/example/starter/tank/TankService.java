package com.example.starter.tank;

import com.example.starter.idempotent.IdempotencyService;
import com.example.starter.idempotent.ReplayResult;
import com.example.starter.station.Station;
import com.example.starter.station.StationRepository;
import com.example.starter.web.BusinessRejectedException;
import com.example.starter.web.ResourceNotFoundException;
import com.example.starter.web.VersionConflictException;
import com.example.starter.web.dto.TankCancelRequest;
import com.example.starter.web.dto.TankCancelResponse;
import com.example.starter.web.dto.TankPlanRequest;
import com.example.starter.web.dto.TankPlanResponse;
import com.example.starter.web.dto.TankReceiptRequest;
import com.example.starter.web.dto.TankReceiptResponse;
import com.example.starter.web.dto.TankRefillRequest;
import com.example.starter.web.dto.TankRefillResponse;
import com.example.starter.web.dto.TankRegisterRequest;
import com.example.starter.web.dto.TankRegisterResponse;
import com.example.starter.web.dto.TankView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 储罐交接台账：登记、受控加注计划（预占/回执/取消）、双人确认补液入账。
 *
 * <ul>
 *   <li>登记：0≤安全储备&lt;容量，0≤期初余额≤容量，数量至多三位小数千克，
 *       一个门站至多绑定一个储罐；未绑定储罐的旧门站行为不变；</li>
 *   <li>计划：先预占所需量，预占后可用量（余额−预占）不得低于安全储备，恰等允许；
 *       低于则生成待补液事件且不下发加注（BLOCKED），不伪报加注成功；</li>
 *   <li>回执：预占转实际消耗并释放未用部分，实际量不得大于预占；
 *       重复回执（同量）返回首次执行结果，不重复扣量；</li>
 *   <li>取消：只释放尚未执行的预占；已执行计划不可取消；</li>
 *   <li>补液：唯一运输批号、两名不同交接人、浓度在许可闭区间（门站目标浓度±容差）、
 *       加入后不超容量（恰等允许），全部满足才原子入账并关闭可满足的待补液事件；
 *       仅关闭事件，不因一次入账自动重发旧加注命令；</li>
 *   <li>守恒：余额=期初+补液−消耗、预占=未执行预占合计，全部由台账分录勾稽，
 *       数值一律按规范值（compareTo）比较；</li>
 *   <li>并发：全部变更在储罐行锁内串行裁决，版本从 1 开始、成功变更加 1，
 *       业务、版本与幂等结果同事务提交，失败回滚不占幂等键。</li>
 * </ul>
 */
@Service
public class TankService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final StationRepository stationRepository;
    private final TankRepository tankRepository;
    private final TankPlanRepository planRepository;
    private final TankRefillRepository refillRepository;
    private final TankRefillEventRepository eventRepository;
    private final TankLedgerRepository ledgerRepository;
    private final IdempotencyService idempotencyService;

    public TankService(StationRepository stationRepository,
                       TankRepository tankRepository,
                       TankPlanRepository planRepository,
                       TankRefillRepository refillRepository,
                       TankRefillEventRepository eventRepository,
                       TankLedgerRepository ledgerRepository,
                       IdempotencyService idempotencyService) {
        this.stationRepository = stationRepository;
        this.tankRepository = tankRepository;
        this.planRepository = planRepository;
        this.refillRepository = refillRepository;
        this.eventRepository = eventRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyService = idempotencyService;
    }

    // ---------------------------------------------------------------- 登记

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
        BigDecimal capacity = requireScale3(request.capacityKg(), "capacityKg");
        BigDecimal safety = requireScale3(request.safetyReserveKg(), "safetyReserveKg");
        BigDecimal balance = requireScale3(request.initialBalanceKg(), "initialBalanceKg");
        if (safety.compareTo(capacity) >= 0) {
            throw new IllegalArgumentException(
                    "safetyReserveKg must be less than capacityKg (0 <= safety reserve < capacity)");
        }
        if (balance.compareTo(capacity) > 0) {
            throw new IllegalArgumentException("initialBalanceKg must not exceed capacityKg");
        }
        Long tankId;
        try {
            tankId = tankRepository.insert(station.id(), request.tankCode(), capacity, safety, balance);
        } catch (DuplicateKeyException duplicate) {
            throw new BusinessRejectedException(
                    "tank code already used or station already bound: " + request.tankCode());
        }
        ledgerRepository.insert(tankId, "REGISTER", balance, ZERO, balance, ZERO,
                "REGISTER", null, request.requestId(), request.registeredAt());
        Tank tank = tankRepository.findById(tankId).orElseThrow();
        return new TankRegisterResponse(tank.id(), station.stationCode(), tank.tankCode(),
                tank.capacityKg(), tank.safetyReserveKg(), tank.balanceKg(), tank.reservedKg(),
                tank.version(), false);
    }

    // ---------------------------------------------------------------- 加注计划

    public TankPlanResponse createPlan(TankPlanRequest request) {
        Tank tank = tankRepository.findByCode(request.tankCode())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "tank not found: " + request.tankCode()));
        ReplayResult<TankPlanResponse> replay = idempotencyService.run(
                request.requestId(), tank.stationId(), "TANK_PLAN", request, 200,
                () -> doCreatePlan(tank.id(), request), TankPlanResponse.class);
        return replay.result();
    }

    private TankPlanResponse doCreatePlan(Long tankId, TankPlanRequest request) {
        BigDecimal required = requireScale3(request.requiredQtyKg(), "requiredQtyKg");
        Tank tank = lockTank(tankId);
        checkVersion(tank, request.expectedVersion());

        BigDecimal available = tank.availableKg();
        if (available.subtract(required).compareTo(tank.safetyReserveKg()) >= 0) {
            // 预占后可用量不低于安全储备（恰等允许）：预占并下发
            BigDecimal reservedAfter = tank.reservedKg().add(required);
            long newVersion = bumpVersion(tank, tank.balanceKg(), reservedAfter);
            Long planId = planRepository.insert(tank.id(), request.requestId(),
                    request.plannedAt(), required, "RESERVED");
            ledgerRepository.insert(tank.id(), "RESERVE", ZERO, required,
                    tank.balanceKg(), reservedAfter, "PLAN", planId,
                    request.requestId(), request.plannedAt());
            return new TankPlanResponse(planId, tank.id(), "RESERVED", true, required,
                    tank.balanceKg(), reservedAfter, tank.balanceKg().subtract(reservedAfter),
                    null, newVersion, false);
        }

        // 预占后可用量低于安全储备：生成待补液事件，不下发加注
        long newVersion = bumpVersion(tank, tank.balanceKg(), tank.reservedKg());
        Long planId = planRepository.insert(tank.id(), request.requestId(),
                request.plannedAt(), required, "BLOCKED");
        BigDecimal deficit = required.subtract(available.subtract(tank.safetyReserveKg()));
        Long eventId = eventRepository.insert(tank.id(), "PLAN", planId, required, deficit);
        return new TankPlanResponse(planId, tank.id(), "BLOCKED", false, required,
                tank.balanceKg(), tank.reservedKg(), available, eventId, newVersion, false);
    }

    // ---------------------------------------------------------------- 执行回执

    public TankReceiptResponse receipt(TankReceiptRequest request) {
        TankPlan plan = planRepository.findById(request.planId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "plan not found: " + request.planId()));
        Tank tank = tankRepository.findById(plan.tankId()).orElseThrow();
        ReplayResult<TankReceiptResponse> replay = idempotencyService.run(
                request.requestId(), tank.stationId(), "TANK_RECEIPT", request, 200,
                () -> doReceipt(tank.id(), request), TankReceiptResponse.class);
        return replay.result();
    }

    private TankReceiptResponse doReceipt(Long tankId, TankReceiptRequest request) {
        BigDecimal actual = requireScale3(request.actualQtyKg(), "actualQtyKg");
        Tank tank = lockTank(tankId);
        checkVersion(tank, request.expectedVersion());
        TankPlan plan = planRepository.findByIdForUpdate(request.planId()).orElseThrow();

        switch (plan.status()) {
            case "RESERVED" -> {
                if (actual.compareTo(plan.requiredQtyKg()) > 0) {
                    throw new BusinessRejectedException(
                            "actual quantity exceeds reserved: " + actual.toPlainString()
                                    + " > " + plan.requiredQtyKg().toPlainString());
                }
                BigDecimal unused = plan.requiredQtyKg().subtract(actual);
                BigDecimal balanceAfter = tank.balanceKg().subtract(actual);
                BigDecimal reservedAfter = tank.reservedKg().subtract(plan.requiredQtyKg());
                long newVersion = bumpVersion(tank, balanceAfter, reservedAfter);
                planRepository.markExecuted(plan.id(), actual, request.executedAt());
                if (actual.signum() > 0) {
                    // 预占转实际消耗：余额与预占同步扣减
                    ledgerRepository.insert(tank.id(), "CONSUME", actual.negate(), actual.negate(),
                            balanceAfter, tank.reservedKg().subtract(actual),
                            "RECEIPT", plan.id(), request.requestId(), request.executedAt());
                }
                if (unused.signum() > 0) {
                    // 释放未用部分
                    ledgerRepository.insert(tank.id(), "RELEASE", ZERO, unused.negate(),
                            balanceAfter, reservedAfter,
                            "RECEIPT", plan.id(), request.requestId(), request.executedAt());
                }
                return new TankReceiptResponse(plan.id(), tank.id(), "EXECUTED", actual, unused,
                        balanceAfter, reservedAfter, newVersion, false);
            }
            case "EXECUTED" -> {
                if (actual.compareTo(plan.actualQtyKg()) == 0) {
                    // 重复回执（同量）：返回首次执行结果，不重复扣量、不变更版本
                    return new TankReceiptResponse(plan.id(), tank.id(), "EXECUTED",
                            plan.actualQtyKg(), plan.requiredQtyKg().subtract(plan.actualQtyKg()),
                            tank.balanceKg(), tank.reservedKg(), tank.version(), false);
                }
                throw new BusinessRejectedException(
                        "plan already executed with quantity " + plan.actualQtyKg().toPlainString());
            }
            case "CANCELLED" -> throw new BusinessRejectedException("plan already cancelled");
            default -> throw new BusinessRejectedException("plan not dispatched (blocked)");
        }
    }

    // ---------------------------------------------------------------- 取消

    public TankCancelResponse cancel(TankCancelRequest request) {
        TankPlan plan = planRepository.findById(request.planId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "plan not found: " + request.planId()));
        Tank tank = tankRepository.findById(plan.tankId()).orElseThrow();
        ReplayResult<TankCancelResponse> replay = idempotencyService.run(
                request.requestId(), tank.stationId(), "TANK_CANCEL", request, 200,
                () -> doCancel(tank.id(), request), TankCancelResponse.class);
        return replay.result();
    }

    private TankCancelResponse doCancel(Long tankId, TankCancelRequest request) {
        Tank tank = lockTank(tankId);
        checkVersion(tank, request.expectedVersion());
        TankPlan plan = planRepository.findByIdForUpdate(request.planId()).orElseThrow();

        switch (plan.status()) {
            case "RESERVED" -> {
                // 只释放尚未执行的预占
                BigDecimal reservedAfter = tank.reservedKg().subtract(plan.requiredQtyKg());
                long newVersion = bumpVersion(tank, tank.balanceKg(), reservedAfter);
                planRepository.markCancelled(plan.id(), request.cancelledAt());
                ledgerRepository.insert(tank.id(), "RELEASE", ZERO, plan.requiredQtyKg().negate(),
                        tank.balanceKg(), reservedAfter, "CANCEL", plan.id(),
                        request.requestId(), request.cancelledAt());
                return new TankCancelResponse(plan.id(), tank.id(), "CANCELLED",
                        plan.requiredQtyKg(), tank.balanceKg(), reservedAfter, newVersion, false);
            }
            case "CANCELLED" -> {
                // 重复取消：预占已释放，返回当前状态，不重复返还
                return new TankCancelResponse(plan.id(), tank.id(), "CANCELLED", ZERO,
                        tank.balanceKg(), tank.reservedKg(), tank.version(), false);
            }
            case "EXECUTED" -> throw new BusinessRejectedException("plan already executed");
            default -> throw new BusinessRejectedException("plan not dispatched (blocked)");
        }
    }

    // ---------------------------------------------------------------- 补液

    public TankRefillResponse refill(TankRefillRequest request) {
        Tank tank = tankRepository.findByCode(request.tankCode())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "tank not found: " + request.tankCode()));
        ReplayResult<TankRefillResponse> replay = idempotencyService.run(
                request.requestId(), tank.stationId(), "TANK_REFILL", request, 200,
                () -> doRefill(tank.id(), request), TankRefillResponse.class);
        return replay.result();
    }

    private TankRefillResponse doRefill(Long tankId, TankRefillRequest request) {
        BigDecimal quantity = requireScale3(request.quantityKg(), "quantityKg");
        BigDecimal concentration = requireScale3(request.concentrationMgM3(), "concentrationMgM3");
        Tank tank = lockTank(tankId);
        checkVersion(tank, request.expectedVersion());

        String firstOperator = request.operators().get(0);
        String secondOperator = request.operators().get(1);
        if (firstOperator.equals(secondOperator)) {
            throw new BusinessRejectedException("refill requires two different handover operators");
        }

        Station station = stationRepository.findById(tank.stationId()).orElseThrow();
        BigDecimal tolerance = station.targetConcentrationMgM3()
                .multiply(station.concentrationTolerancePct())
                .divide(BigDecimal.valueOf(100), 3, RoundingMode.HALF_UP);
        BigDecimal lowerBound = station.targetConcentrationMgM3().subtract(tolerance);
        BigDecimal upperBound = station.targetConcentrationMgM3().add(tolerance);
        // 许可闭区间：恰在边界允许入账
        if (concentration.compareTo(lowerBound) < 0 || concentration.compareTo(upperBound) > 0) {
            throw new BusinessRejectedException(
                    "concentration out of permitted range [" + lowerBound.toPlainString()
                            + ", " + upperBound.toPlainString() + "]: " + concentration.toPlainString());
        }

        BigDecimal balanceAfter = tank.balanceKg().add(quantity);
        // 加入后不超容量，恰等容量允许
        if (balanceAfter.compareTo(tank.capacityKg()) > 0) {
            throw new BusinessRejectedException(
                    "refill would exceed tank capacity: " + balanceAfter.toPlainString()
                            + " > " + tank.capacityKg().toPlainString());
        }

        Long refillId;
        try {
            refillId = refillRepository.insert(tank.id(), request.requestId(),
                    request.transportBatchNo(), quantity, concentration,
                    firstOperator, secondOperator, request.receivedAt());
        } catch (DuplicateKeyException duplicate) {
            throw new BusinessRejectedException(
                    "transport batch already posted: " + request.transportBatchNo());
        }
        long newVersion = bumpVersion(tank, balanceAfter, tank.reservedKg());
        ledgerRepository.insert(tank.id(), "REFILL", quantity, ZERO, balanceAfter,
                tank.reservedKg(), "REFILL", refillId, request.requestId(), request.receivedAt());

        // 关闭可满足的待补液事件：仅关闭，不因一次入账自动重发旧加注命令
        BigDecimal available = balanceAfter.subtract(tank.reservedKg());
        List<Long> closedEventIds = new ArrayList<>();
        for (TankRefillEvent event : eventRepository.findOpenForUpdate(tank.id())) {
            if (available.subtract(event.requiredQtyKg()).compareTo(tank.safetyReserveKg()) >= 0) {
                eventRepository.close(event.id(), refillId);
                closedEventIds.add(event.id());
            }
        }
        return new TankRefillResponse(refillId, tank.id(), request.transportBatchNo(), quantity,
                balanceAfter, tank.reservedKg(), available, closedEventIds, newVersion, false);
    }

    // ---------------------------------------------------------------- 压力联动闸控

    /**
     * 压力联动消耗闸控（与压力检测同事务）：绑定储罐的门站按可用量判定，
     * 不足时生成待补液事件并返回 false，调用方不得伪报加注成功；
     * 未绑定储罐的旧门站直接返回 true，行为不变。
     *
     * @return true 已扣减（或无需扣减）可下发加注；false 可用量不足未扣减
     */
    public boolean consumeForLinkage(Long stationId, BigDecimal doseKg, Long taskId,
                                     String requestId, LocalDateTime detectedAt) {
        Tank tank = tankRepository.findByStationIdForUpdate(stationId).orElse(null);
        if (tank == null || doseKg.signum() <= 0) {
            return true;
        }
        BigDecimal available = tank.availableKg();
        if (available.subtract(doseKg).compareTo(tank.safetyReserveKg()) >= 0) {
            BigDecimal balanceAfter = tank.balanceKg().subtract(doseKg);
            bumpVersion(tank, balanceAfter, tank.reservedKg());
            ledgerRepository.insert(tank.id(), "CONSUME", doseKg.negate(), ZERO,
                    balanceAfter, tank.reservedKg(), "PRESSURE", taskId, requestId, detectedAt);
            return true;
        }
        BigDecimal deficit = doseKg.subtract(available.subtract(tank.safetyReserveKg()));
        bumpVersion(tank, tank.balanceKg(), tank.reservedKg());
        eventRepository.insert(tank.id(), "PRESSURE", null, doseKg, deficit);
        return false;
    }

    // ---------------------------------------------------------------- 查询

    /**
     * 储罐台账视图：余额、预占、可用量与当前版本。
     */
    public TankView tankView(String tankCode) {
        Tank tank = tankRepository.findByCode(tankCode)
                .orElseThrow(() -> new ResourceNotFoundException("tank not found: " + tankCode));
        Station station = stationRepository.findById(tank.stationId()).orElseThrow();
        return new TankView(tank.id(), station.stationCode(), tank.tankCode(), tank.capacityKg(),
                tank.safetyReserveKg(), tank.balanceKg(), tank.reservedKg(), tank.availableKg(),
                tank.version());
    }

    /**
     * 储罐全部加注计划（含 BLOCKED 未下发计划）。
     */
    public List<TankPlan> plans(String tankCode) {
        Tank tank = tankRepository.findByCode(tankCode)
                .orElseThrow(() -> new ResourceNotFoundException("tank not found: " + tankCode));
        return planRepository.listByTank(tank.id());
    }

    /**
     * 储罐台账分录（守恒勾稽明细）。
     */
    public List<TankLedgerEntry> ledger(String tankCode) {
        Tank tank = tankRepository.findByCode(tankCode)
                .orElseThrow(() -> new ResourceNotFoundException("tank not found: " + tankCode));
        return ledgerRepository.listByTank(tank.id());
    }

    /**
     * 储罐待补液事件（含已关闭）。
     */
    public List<TankRefillEvent> refillEvents(String tankCode) {
        Tank tank = tankRepository.findByCode(tankCode)
                .orElseThrow(() -> new ResourceNotFoundException("tank not found: " + tankCode));
        return eventRepository.listByTank(tank.id());
    }

    // ---------------------------------------------------------------- 内部

    private Tank lockTank(Long tankId) {
        return tankRepository.findByIdForUpdate(tankId).orElseThrow();
    }

    private void checkVersion(Tank tank, long expectedVersion) {
        if (tank.version() != expectedVersion) {
            throw new VersionConflictException("tank " + tank.tankCode()
                    + " version mismatch: expected=" + expectedVersion
                    + ", current=" + tank.version());
        }
    }

    /**
     * 余额/预占变更并递增版本（成功变更加 1）；行锁内版本条件更新必中，否则为并发缺陷。
     */
    private long bumpVersion(Tank tank, BigDecimal balanceAfter, BigDecimal reservedAfter) {
        long newVersion = tank.version() + 1;
        int updated = tankRepository.updateBalanceReservedVersion(tank.id(), balanceAfter,
                reservedAfter, newVersion, tank.version());
        if (updated != 1) {
            throw new VersionConflictException("tank " + tank.tankCode()
                    + " concurrent modification detected");
        }
        return newVersion;
    }

    /**
     * 千克数量至多三位小数；合法值原样返回，比较一律按规范值（compareTo）。
     */
    private BigDecimal requireScale3(BigDecimal value, String field) {
        if (value.stripTrailingZeros().scale() > 3) {
            throw new IllegalArgumentException(field + " supports at most 3 decimal places");
        }
        return value;
    }
}
