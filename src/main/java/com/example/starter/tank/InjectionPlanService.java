package com.example.starter.tank;

import com.example.starter.idempotent.IdempotencyService;
import com.example.starter.idempotent.ReplayResult;
import com.example.starter.station.Station;
import com.example.starter.station.StationRepository;
import com.example.starter.web.BusinessRejectedException;
import com.example.starter.web.ResourceNotFoundException;
import com.example.starter.web.VersionConflictException;
import com.example.starter.web.dto.InjectionCancelRequest;
import com.example.starter.web.dto.InjectionCancelResponse;
import com.example.starter.web.dto.InjectionPlanRequest;
import com.example.starter.web.dto.InjectionPlanResponse;
import com.example.starter.web.dto.InjectionReceiptRequest;
import com.example.starter.web.dto.InjectionReceiptResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 受控加注计划：预占、执行回执与取消。
 *
 * <ul>
 *   <li>计划先预占所需量，预占后可用量（余额−预占）不得低于安全储备，恰等允许；
 *       低于则生成待补液事件且不下发加注（BLOCKED），不伪报加注成功；</li>
 *   <li>回执将预占转实际消耗并释放未用部分，实际量不得大于预占；
 *       取消只释放尚未执行的预占；已终结计划再次回执/取消为业务拒绝（422）；</li>
 *   <li>每次成功变更携带 expectedVersion 校验（不一致 409）并使版本加 1，
 *       台账明细与余额、预占同事务守恒；</li>
 *   <li>全程在幂等事务内执行，同键同参重放首次冻结结果，重复回执不重复扣量。</li>
 * </ul>
 */
@Service
public class InjectionPlanService {

    private final StationRepository stationRepository;
    private final TankRepository tankRepository;
    private final InjectionPlanRepository planRepository;
    private final RefillEventRepository refillEventRepository;
    private final TankLedgerRepository ledgerRepository;
    private final IdempotencyService idempotencyService;

    public InjectionPlanService(StationRepository stationRepository,
                                TankRepository tankRepository,
                                InjectionPlanRepository planRepository,
                                RefillEventRepository refillEventRepository,
                                TankLedgerRepository ledgerRepository,
                                IdempotencyService idempotencyService) {
        this.stationRepository = stationRepository;
        this.tankRepository = tankRepository;
        this.planRepository = planRepository;
        this.refillEventRepository = refillEventRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyService = idempotencyService;
    }

    public InjectionPlanResponse plan(InjectionPlanRequest request) {
        Station station = stationRepository.findByCode(request.stationCode())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "station not found: " + request.stationCode()));
        ReplayResult<InjectionPlanResponse> replay = idempotencyService.run(
                request.requestId(), station.id(), "INJECTION_PLAN", request, 200,
                () -> doPlan(station, request), InjectionPlanResponse.class);
        return replay.result();
    }

    public InjectionReceiptResponse receipt(InjectionReceiptRequest request) {
        ReplayResult<InjectionReceiptResponse> replay = idempotencyService.run(
                request.requestId(), null, "INJECTION_RECEIPT", request, 200,
                () -> doReceipt(request), InjectionReceiptResponse.class);
        return replay.result();
    }

    public InjectionCancelResponse cancel(InjectionCancelRequest request) {
        ReplayResult<InjectionCancelResponse> replay = idempotencyService.run(
                request.requestId(), null, "INJECTION_CANCEL", request, 200,
                () -> doCancel(request), InjectionCancelResponse.class);
        return replay.result();
    }

    private InjectionPlanResponse doPlan(Station station, InjectionPlanRequest request) {
        BigDecimal planned = TankService.requireKgScale(request.plannedKg(), "plannedKg");
        OdorantTank tank = requireTankForUpdate(station.id());
        requireVersion(tank, request.expectedVersion());

        BigDecimal availableAfter = tank.availableKg().subtract(planned);
        if (availableAfter.compareTo(tank.safetyReserveKg()) < 0) {
            // 低于安全储备：生成待补液事件，不下发加注，不预占、不变更版本
            Long planId = planRepository.insert(station.id(), tank.id(), null,
                    request.requestId(), planned, "BLOCKED", request.plannedAt());
            BigDecimal deficit = tank.safetyReserveKg().subtract(availableAfter);
            Long eventId = refillEventRepository.insert(station.id(), tank.id(), planId,
                    null, "PLAN", deficit);
            return new InjectionPlanResponse(planId, "BLOCKED", planned, false,
                    tank.balanceKg(), tank.reservedKg(), tank.availableKg(),
                    eventId, tank.version(), false);
        }

        // 恰等安全储备也允许：预占成立并下发加注
        BigDecimal reservedAfter = tank.reservedKg().add(planned);
        Long planId = planRepository.insert(station.id(), tank.id(), null,
                request.requestId(), planned, "RESERVED", request.plannedAt());
        tankRepository.applyMutation(tank.id(), tank.balanceKg(), reservedAfter);
        ledgerRepository.append(tank.id(), "RESERVE", planned,
                tank.balanceKg(), reservedAfter, "PLAN", planId, request.requestId());
        return new InjectionPlanResponse(planId, "RESERVED", planned, true,
                tank.balanceKg(), reservedAfter, tank.balanceKg().subtract(reservedAfter),
                null, tank.version() + 1, false);
    }

    private InjectionReceiptResponse doReceipt(InjectionReceiptRequest request) {
        BigDecimal actual = TankService.requireKgScale(request.actualKg(), "actualKg");
        InjectionPlan plan = requirePlanForUpdate(request.planId());
        if (!"RESERVED".equals(plan.status())) {
            // 已执行/已取消/未下发：终结态不接受回执，重复回执由幂等层重放
            throw new BusinessRejectedException(
                    "plan not in RESERVED status: id=" + plan.id() + " status=" + plan.status());
        }
        OdorantTank tank = requireTankForUpdateById(plan.tankId());
        requireVersion(tank, request.expectedVersion());
        if (actual.compareTo(plan.plannedKg()) > 0) {
            throw new BusinessRejectedException(
                    "actualKg must not exceed plannedKg: " + actual + " > " + plan.plannedKg());
        }

        BigDecimal released = plan.plannedKg().subtract(actual);
        BigDecimal balanceAfter = tank.balanceKg().subtract(actual);
        BigDecimal reservedAfter = tank.reservedKg().subtract(plan.plannedKg());
        planRepository.markExecuted(plan.id(), actual, request.executedAt());
        tankRepository.applyMutation(tank.id(), balanceAfter, reservedAfter);
        ledgerRepository.append(tank.id(), "CONSUME", actual,
                balanceAfter, reservedAfter.add(released), "RECEIPT", plan.id(), request.requestId());
        if (released.compareTo(BigDecimal.ZERO) > 0) {
            ledgerRepository.append(tank.id(), "RELEASE", released,
                    balanceAfter, reservedAfter, "RECEIPT", plan.id(), request.requestId());
        }
        return new InjectionReceiptResponse(plan.id(), "EXECUTED", plan.plannedKg(), actual,
                released, balanceAfter, reservedAfter, tank.version() + 1, false);
    }

    private InjectionCancelResponse doCancel(InjectionCancelRequest request) {
        InjectionPlan plan = requirePlanForUpdate(request.planId());
        if (!"RESERVED".equals(plan.status())) {
            // 只释放尚未执行的预占：已回执/已取消/未下发的计划无预占可释放
            throw new BusinessRejectedException(
                    "plan not in RESERVED status: id=" + plan.id() + " status=" + plan.status());
        }
        OdorantTank tank = requireTankForUpdateById(plan.tankId());
        requireVersion(tank, request.expectedVersion());

        BigDecimal reservedAfter = tank.reservedKg().subtract(plan.plannedKg());
        planRepository.markCancelled(plan.id(), request.cancelledAt());
        tankRepository.applyMutation(tank.id(), tank.balanceKg(), reservedAfter);
        ledgerRepository.append(tank.id(), "RELEASE", plan.plannedKg(),
                tank.balanceKg(), reservedAfter, "CANCEL", plan.id(), request.requestId());
        return new InjectionCancelResponse(plan.id(), "CANCELLED", plan.plannedKg(),
                tank.balanceKg(), reservedAfter, tank.version() + 1, false);
    }

    private OdorantTank requireTankForUpdate(Long stationId) {
        OdorantTank tank = tankRepository.selectForUpdateByStationId(stationId);
        if (tank == null) {
            throw new ResourceNotFoundException("tank not bound for station: " + stationId);
        }
        return tank;
    }

    private OdorantTank requireTankForUpdateById(Long tankId) {
        OdorantTank tank = tankRepository.selectForUpdateById(tankId);
        if (tank == null) {
            throw new ResourceNotFoundException("tank not found: " + tankId);
        }
        return tank;
    }

    private InjectionPlan requirePlanForUpdate(Long planId) {
        InjectionPlan plan = planRepository.selectForUpdate(planId);
        if (plan == null) {
            throw new ResourceNotFoundException("injection plan not found: " + planId);
        }
        return plan;
    }

    private void requireVersion(OdorantTank tank, Integer expectedVersion) {
        if (expectedVersion == null || expectedVersion != tank.version()) {
            throw new VersionConflictException(
                    "expectedVersion " + expectedVersion + " != current " + tank.version());
        }
    }
}
