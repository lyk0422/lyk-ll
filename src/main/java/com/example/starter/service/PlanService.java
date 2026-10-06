package com.example.starter.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.domain.Interval;
import com.example.starter.domain.PilotPlanEntity;
import com.example.starter.domain.PlanStatus;
import com.example.starter.domain.ResourceType;
import com.example.starter.error.BusinessException;
import com.example.starter.repo.BreachRepository;
import com.example.starter.repo.LockRepository;
import com.example.starter.repo.PlanRepository;
import com.example.starter.repo.ResourceRepository;
import com.example.starter.web.dto.PlanResponse;

/**
 * 引航计划核心服务：申请、编排、确认、执行、核销、取消、改期。
 * 确认/改期的资源锁定与状态流转在同一事务内完成。
 */
@Service
public class PlanService {

    /** 迟到取消宽限：执行前 2 小时（恰满 2 小时仍可免费取消）。 */
    static final long LATE_CANCEL_HOURS = 2L;

    private final PlanRepository planRepository;
    private final LockRepository lockRepository;
    private final ResourceRepository resourceRepository;
    private final BreachRepository breachRepository;
    private final WorkHourValidator workHourValidator;
    private final Clock clock;

    public PlanService(PlanRepository planRepository, LockRepository lockRepository,
            ResourceRepository resourceRepository, BreachRepository breachRepository,
            WorkHourValidator workHourValidator, Clock clock) {
        this.planRepository = planRepository;
        this.lockRepository = lockRepository;
        this.resourceRepository = resourceRepository;
        this.breachRepository = breachRepository;
        this.workHourValidator = workHourValidator;
        this.clock = clock;
    }

    /** 申请：创建 APPLIED 状态计划。 */
    @Transactional
    public PlanResponse apply(String planNo, String shipName, String voyageNo) {
        long id;
        try {
            id = planRepository.insert(planNo, shipName, voyageNo, now());
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("PLAN_NO_CONFLICT", "计划编号已存在: " + planNo);
        }
        return toResponse(mustFind(id));
    }

    /** 编排：绑定引航员、拖轮组合与潮水窗口，并做工时与占用校验。 */
    @Transactional
    public PlanResponse arrange(long planId, long pilotId, List<Long> tugboatIds,
            LocalDateTime tideStart, LocalDateTime tideEnd) {
        PilotPlanEntity plan = mustFind(planId);
        requireStatus(plan, PlanStatus.APPLIED, "仅申请状态的计划可编排");
        Interval window = newWindow(tideStart, tideEnd);
        List<Long> tugs = validateResources(pilotId, tugboatIds);
        validateAvailability(pilotId, tugs, window, null);
        if (planRepository.arrange(planId, pilotId, tideStart, tideEnd) != 1) {
            throw BusinessException.unprocessable("INVALID_STATE", "计划状态已变化，编排失败");
        }
        resourceRepository.replacePlanTugs(planId, tugs);
        return toResponse(mustFind(planId));
    }

    /** 确认：同事务内重校验、锁定引航员与拖轮并流转状态。 */
    @Transactional
    public PlanResponse confirm(long planId) {
        PilotPlanEntity plan = mustFind(planId);
        requireStatus(plan, PlanStatus.ARRANGED, "仅编排状态的计划可确认");
        Interval window = newWindow(plan.tideStart(), plan.tideEnd());
        List<Long> tugs = resourceRepository.findTugIdsByPlan(planId);
        lockRepository.lockResourceRows(ResourceType.PILOT, List.of(plan.pilotId()));
        lockRepository.lockResourceRows(ResourceType.TUG, tugs);
        validateAvailability(plan.pilotId(), tugs, window, null);
        lockRepository.insertLock(ResourceType.PILOT, plan.pilotId(), planId, window.start(), window.end());
        for (Long tugId : tugs) {
            lockRepository.insertLock(ResourceType.TUG, tugId, planId, window.start(), window.end());
        }
        if (planRepository.confirm(planId, now()) != 1) {
            throw BusinessException.unprocessable("INVALID_STATE", "计划状态已变化，确认失败");
        }
        return toResponse(mustFind(planId));
    }

    /** 执行：同一引航员同时刻仅允许一条执行中计划（数据库主键保证）。 */
    @Transactional
    public PlanResponse start(long planId) {
        PilotPlanEntity plan = mustFind(planId);
        requireStatus(plan, PlanStatus.CONFIRMED, "仅确认状态的计划可开始执行");
        try {
            lockRepository.insertExecutingLock(plan.pilotId(), planId);
        } catch (DuplicateKeyException e) {
            throw BusinessException.unprocessable("PILOT_EXECUTING_CONFLICT",
                    "同一引航员同时刻只允许一条执行中计划");
        }
        if (planRepository.start(planId, now()) != 1) {
            throw BusinessException.unprocessable("INVALID_STATE", "计划状态已变化，执行失败");
        }
        return toResponse(mustFind(planId));
    }

    /** 核销：流转状态并释放全部资源锁定。 */
    @Transactional
    public PlanResponse settle(long planId) {
        PilotPlanEntity plan = mustFind(planId);
        requireStatus(plan, PlanStatus.EXECUTING, "仅执行中的计划可核销");
        if (planRepository.settle(planId, now()) != 1) {
            throw BusinessException.unprocessable("INVALID_STATE", "计划状态已变化，核销失败");
        }
        lockRepository.releaseByPlan(planId);
        lockRepository.deleteExecutingLock(plan.pilotId());
        return toResponse(mustFind(planId));
    }

    /** 取消：执行前 2 小时外免费取消；不足 2 小时收取合成违约记录；恰满 2 小时仍可取消。 */
    @Transactional
    public PlanResponse cancel(long planId, String reason) {
        PilotPlanEntity plan = mustFind(planId);
        if (plan.status() != PlanStatus.APPLIED && plan.status() != PlanStatus.ARRANGED
                && plan.status() != PlanStatus.CONFIRMED) {
            throw BusinessException.unprocessable("INVALID_STATE",
                    "当前状态 " + plan.status() + " 不允许取消");
        }
        LocalDateTime now = now();
        if (plan.status() == PlanStatus.CONFIRMED) {
            LocalDateTime deadline = plan.tideStart().minusHours(LATE_CANCEL_HOURS);
            if (now.isAfter(deadline)) {
                breachRepository.insert(planId,
                        "计划 " + plan.planNo() + " 距执行窗口不足 2 小时取消，收取合成违约记录", now);
            }
        }
        lockRepository.releaseByPlan(planId);
        if (planRepository.cancel(planId, now, reason) != 1) {
            throw BusinessException.unprocessable("INVALID_STATE", "计划状态已变化，取消失败");
        }
        return toResponse(mustFind(planId));
    }

    /** 改期：重新校验引航员工时与拖轮占用，结论与重新锁定在同一事务内完成。 */
    @Transactional
    public PlanResponse reschedule(long planId, LocalDateTime tideStart, LocalDateTime tideEnd) {
        PilotPlanEntity plan = mustFind(planId);
        requireStatus(plan, PlanStatus.CONFIRMED, "仅确认状态的计划可改期");
        Interval window = newWindow(tideStart, tideEnd);
        List<Long> tugs = resourceRepository.findTugIdsByPlan(planId);
        lockRepository.lockResourceRows(ResourceType.PILOT, List.of(plan.pilotId()));
        lockRepository.lockResourceRows(ResourceType.TUG, tugs);
        validateAvailability(plan.pilotId(), tugs, window, planId);
        lockRepository.releaseByPlan(planId);
        lockRepository.insertLock(ResourceType.PILOT, plan.pilotId(), planId, window.start(), window.end());
        for (Long tugId : tugs) {
            lockRepository.insertLock(ResourceType.TUG, tugId, planId, window.start(), window.end());
        }
        if (planRepository.reschedule(planId, tideStart, tideEnd) != 1) {
            throw BusinessException.unprocessable("INVALID_STATE", "计划状态已变化，改期失败");
        }
        return toResponse(mustFind(planId));
    }

    /** 查询单个计划。 */
    @Transactional(readOnly = true)
    public PlanResponse get(long planId) {
        return toResponse(mustFind(planId));
    }

    /** 查询全部计划（按ID升序）。 */
    @Transactional(readOnly = true)
    public List<PlanResponse> list() {
        return planRepository.findAll().stream().map(this::toResponse).toList();
    }

    private PilotPlanEntity mustFind(long planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> BusinessException.notFound("计划不存在: " + planId));
    }

    private void requireStatus(PilotPlanEntity plan, PlanStatus expected, String message) {
        if (plan.status() != expected) {
            throw BusinessException.unprocessable("INVALID_STATE",
                    message + "，当前状态: " + plan.status());
        }
    }

    private Interval newWindow(LocalDateTime start, LocalDateTime end) {
        try {
            return new Interval(start, end);
        } catch (IllegalArgumentException e) {
            throw BusinessException.unprocessable("INVALID_WINDOW", "潮水窗口非法: " + e.getMessage());
        }
    }

    /** 校验引航员与拖轮存在性，返回去重排序后的拖轮ID列表。 */
    private List<Long> validateResources(long pilotId, List<Long> tugboatIds) {
        if (!resourceRepository.pilotExists(pilotId)) {
            throw BusinessException.unprocessable("PILOT_NOT_FOUND", "引航员不存在: " + pilotId);
        }
        List<Long> tugs = new LinkedHashSet<>(tugboatIds).stream().sorted().toList();
        if (tugs.isEmpty()) {
            throw BusinessException.unprocessable("TUG_REQUIRED", "每条引航计划须绑定拖轮组合");
        }
        List<Long> existing = resourceRepository.findExistingTugIds(tugs);
        if (existing.size() != tugs.size()) {
            throw BusinessException.unprocessable("TUG_NOT_FOUND", "部分拖轮不存在: " + tugboatIds);
        }
        return tugs;
    }

    /**
     * 占用与工时校验：半开区间重叠判定（端点相接不冲突），引航员连续作业与单日累计工时。
     *
     * @param excludePlanId 改期时排除本计划既有锁定
     */
    private void validateAvailability(long pilotId, List<Long> tugIds, Interval window, Long excludePlanId) {
        if (!lockRepository.findOverlappingActiveLocks(ResourceType.PILOT, pilotId,
                window.start(), window.end(), excludePlanId).isEmpty()) {
            throw BusinessException.unprocessable("PILOT_OCCUPIED", "引航员在该潮水窗口已被占用");
        }
        for (Long tugId : tugIds) {
            if (!lockRepository.findOverlappingActiveLocks(ResourceType.TUG, tugId,
                    window.start(), window.end(), excludePlanId).isEmpty()) {
                throw BusinessException.unprocessable("TUG_OCCUPIED", "拖轮 " + tugId + " 在该潮水窗口已被占用");
            }
        }
        List<Interval> existing = lockRepository.findActiveWindows(ResourceType.PILOT, pilotId, excludePlanId);
        List<String> violations = workHourValidator.validate(existing, window);
        if (!violations.isEmpty()) {
            throw BusinessException.unprocessable("WORK_HOUR_VIOLATION", String.join("；", violations));
        }
    }

    private PlanResponse toResponse(PilotPlanEntity entity) {
        return PlanResponse.of(entity, resourceRepository.findTugIdsByPlan(entity.id()),
                breachRepository.countByPlan(entity.id()));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
