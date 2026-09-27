package com.example.starter.tank;

import com.example.starter.idempotent.IdempotencyService;
import com.example.starter.idempotent.ReplayResult;
import com.example.starter.station.Station;
import com.example.starter.station.StationRepository;
import com.example.starter.web.BusinessRejectedException;
import com.example.starter.web.ResourceNotFoundException;
import com.example.starter.web.dto.InjectionCancelRequest;
import com.example.starter.web.dto.InjectionPlanRequest;
import com.example.starter.web.dto.InjectionPlanResponse;
import com.example.starter.web.dto.InjectionReceiptRequest;
import com.example.starter.web.dto.PlanActionResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 受控加注计划：预占 → 回执/取消的全生命周期计量。
 *
 * <ul>
 *   <li>预占后可用量（余额−预占）不得低于安全储备，恰等允许；低于则生成待补液事件
 *       且不下发加注（计划记 REJECTED，不伪报加注成功）；</li>
 *   <li>回执将预占转实际消耗并释放未用部分，实际量不得大于预占；重复回执
 *       （同键重放除外）拒绝且不重复扣量；</li>
 *   <li>取消只释放尚未执行的预占；已回执计划不可取消；</li>
 *   <li>全部计量经储罐行锁串行，版本随成功变更加 1（REJECTED 未改储罐，版本不变）。</li>
 * </ul>
 */
@Service
public class InjectionPlanService {

    private static final BigDecimal ZERO_KG = BigDecimal.ZERO.setScale(3, RoundingMode.UNNECESSARY);

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

    public PlanActionResponse receipt(InjectionReceiptRequest request) {
        Station station = stationRepository.findByCode(request.stationCode())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "station not found: " + request.stationCode()));
        ReplayResult<PlanActionResponse> replay = idempotencyService.run(
                request.requestId(), station.id(), "INJECTION_RECEIPT", request, 200,
                () -> doReceipt(station, request), PlanActionResponse.class);
        return replay.result();
    }

    public PlanActionResponse cancel(InjectionCancelRequest request) {
        Station station = stationRepository.findByCode(request.stationCode())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "station not found: " + request.stationCode()));
        ReplayResult<PlanActionResponse> replay = idempotencyService.run(
                request.requestId(), station.id(), "INJECTION_CANCEL", request, 200,
                () -> doCancel(station, request), PlanActionResponse.class);
        return replay.result();
    }

    /**
     * 压力联动内部预占入口（同事务）：储备足够则预占并允许下发加注；
     * 不足则生成待补液事件并记 REJECTED 计划，调用方须继续原压力处置但不得伪报加注成功。
     * 未绑定储罐返回 null（旧行为不变）。
     */
    public LinkageReserveResult reserveForLinkage(Station station, Long taskId, String requestId,
                                                  BigDecimal requiredKg, LocalDateTime plannedAt) {
        Tank tank = tankRepository.findForUpdate(station.id()).orElse(null);
        if (tank == null) {
            return null;
        }
        BigDecimal required = TankSupport.kg(requiredKg, "requiredKg");
        if (canReserve(tank, required)) {
            BigDecimal reservedAfter = tank.reservedKg().add(required);
            long newVersion = tank.version() + 1;
            tankRepository.updateBalance(tank.id(), tank.balanceKg(), reservedAfter, newVersion);
            planRepository.insert(station.id(), tank.id(), requestId, "LINKAGE", taskId,
                    required, "PREOCCUPIED", plannedAt);
            ledgerRepository.insert(tank.id(), station.id(), "RESERVE", requestId,
                    ZERO_KG, required, tank.balanceKg(), reservedAfter, plannedAt.toLocalDate());
            return new LinkageReserveResult(true, null, newVersion);
        }
        Long planId = planRepository.insert(station.id(), tank.id(), requestId, "LINKAGE", taskId,
                required, "REJECTED", plannedAt);
        Long eventId = createRefillEvent(station, tank, planId, required);
        return new LinkageReserveResult(false, eventId, tank.version());
    }

    private InjectionPlanResponse doPlan(Station station, InjectionPlanRequest request) {
        BigDecimal required = TankSupport.kg(request.requiredKg(), "requiredKg");
        Tank tank = tankRepository.findForUpdate(station.id())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "tank not found for station: " + station.stationCode()));
        TankSupport.requireVersion(tank, request.expectedVersion());

        if (canReserve(tank, required)) {
            BigDecimal reservedAfter = tank.reservedKg().add(required);
            long newVersion = tank.version() + 1;
            tankRepository.updateBalance(tank.id(), tank.balanceKg(), reservedAfter, newVersion);
            Long planId = planRepository.insert(station.id(), tank.id(), request.requestId(),
                    "MANUAL", null, required, "PREOCCUPIED", request.plannedAt());
            ledgerRepository.insert(tank.id(), station.id(), "RESERVE", request.requestId(),
                    ZERO_KG, required, tank.balanceKg(), reservedAfter,
                    request.plannedAt().toLocalDate());
            return new InjectionPlanResponse(planId, request.requestId(), "PREOCCUPIED", true,
                    required, tank.balanceKg().subtract(reservedAfter), null, newVersion, false);
        }

        // 储备不足：生成待补液事件，不下发加注（不伪报加注成功），储罐计量不变、版本不变
        Long planId = planRepository.insert(station.id(), tank.id(), request.requestId(),
                "MANUAL", null, required, "REJECTED", request.plannedAt());
        Long eventId = createRefillEvent(station, tank, planId, required);
        return new InjectionPlanResponse(planId, request.requestId(), "REJECTED", false,
                required, tank.availableKg(), eventId, tank.version(), false);
    }

    private PlanActionResponse doReceipt(Station station, InjectionReceiptRequest request) {
        BigDecimal actual = TankSupport.kg(request.actualKg(), "actualKg");
        Tank tank = tankRepository.findForUpdate(station.id())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "tank not found for station: " + station.stationCode()));
        TankSupport.requireVersion(tank, request.expectedVersion());

        InjectionPlanRepository.PlanRow plan = planRepository.findForUpdate(request.planRequestId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "injection plan not found: " + request.planRequestId()));
        if (!plan.tankId().equals(tank.id())) {
            throw new ResourceNotFoundException(
                    "injection plan not found for station: " + request.planRequestId());
        }
        if (!"PREOCCUPIED".equals(plan.status())) {
            // 重复回执/回执已取消计划：拒绝且不重复扣量
            throw new BusinessRejectedException(
                    "plan not awaiting receipt: " + request.planRequestId()
                            + " status=" + plan.status());
        }
        if (actual.compareTo(plan.requiredKg()) > 0) {
            throw new BusinessRejectedException(
                    "actual exceeds preoccupied: " + actual + " > " + plan.requiredKg());
        }

        BigDecimal balanceAfter = tank.balanceKg().subtract(actual);
        BigDecimal reservedAfter = tank.reservedKg().subtract(plan.requiredKg());
        BigDecimal released = plan.requiredKg().subtract(actual);
        long newVersion = tank.version() + 1;
        tankRepository.updateBalance(tank.id(), balanceAfter, reservedAfter, newVersion);
        planRepository.markExecuted(plan.id(), actual);
        ledgerRepository.insert(tank.id(), station.id(), "EXECUTE", request.requestId(),
                actual.negate(), plan.requiredKg().negate(), balanceAfter, reservedAfter,
                request.executedAt().toLocalDate());
        return new PlanActionResponse(plan.id(), plan.requestId(), "EXECUTED", actual, released,
                balanceAfter, reservedAfter, newVersion, false);
    }

    private PlanActionResponse doCancel(Station station, InjectionCancelRequest request) {
        Tank tank = tankRepository.findForUpdate(station.id())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "tank not found for station: " + station.stationCode()));
        TankSupport.requireVersion(tank, request.expectedVersion());

        InjectionPlanRepository.PlanRow plan = planRepository.findForUpdate(request.planRequestId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "injection plan not found: " + request.planRequestId()));
        if (!plan.tankId().equals(tank.id())) {
            throw new ResourceNotFoundException(
                    "injection plan not found for station: " + request.planRequestId());
        }
        if (!"PREOCCUPIED".equals(plan.status())) {
            // 只释放尚未执行的预占：已回执/已取消计划不可再取消
            throw new BusinessRejectedException(
                    "plan not cancellable: " + request.planRequestId()
                            + " status=" + plan.status());
        }

        BigDecimal reservedAfter = tank.reservedKg().subtract(plan.requiredKg());
        long newVersion = tank.version() + 1;
        tankRepository.updateBalance(tank.id(), tank.balanceKg(), reservedAfter, newVersion);
        planRepository.markCancelled(plan.id());
        ledgerRepository.insert(tank.id(), station.id(), "RELEASE", request.requestId(),
                ZERO_KG, plan.requiredKg().negate(), tank.balanceKg(), reservedAfter,
                request.cancelledAt().toLocalDate());
        return new PlanActionResponse(plan.id(), plan.requestId(), "CANCELLED", null,
                plan.requiredKg(), tank.balanceKg(), reservedAfter, newVersion, false);
    }

    /**
     * 预占可行性：预占后可用量不得低于安全储备，恰等允许。
     */
    private boolean canReserve(Tank tank, BigDecimal required) {
        return tank.availableKg().subtract(required).compareTo(tank.safetyReserveKg()) >= 0;
    }

    private Long createRefillEvent(Station station, Tank tank, Long planId, BigDecimal required) {
        BigDecimal shortage = required.add(tank.safetyReserveKg()).subtract(tank.availableKg());
        return refillEventRepository.insert(station.id(), tank.id(), planId, required, shortage);
    }

    /**
     * 联动预占结果。
     *
     * @param dispatched    是否允许下发加注
     * @param refillEventId 储备不足时生成的待补液事件ID
     * @param version       处理后储罐版本
     */
    public record LinkageReserveResult(boolean dispatched, Long refillEventId, long version) {
    }
}
