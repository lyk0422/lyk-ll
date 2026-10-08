package com.example.starter.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.example.starter.domain.HandoverStatus;
import com.example.starter.domain.PlanStatus;
import com.example.starter.store.IdempotencyRepository;
import com.example.starter.store.PlanRepository;
import com.example.starter.store.PlanRepository.PlanRow;
import com.example.starter.web.ApiException;
import com.example.starter.web.dto.HandoverResponse;
import com.example.starter.web.dto.PlanDetailResponse;
import com.example.starter.web.dto.PlanResponse;
import com.example.starter.web.dto.Requests.ApplyRequest;
import com.example.starter.web.dto.Requests.CancelRequest;
import com.example.starter.web.dto.Requests.ConfirmRequest;
import com.example.starter.web.dto.Requests.RescheduleRequest;
import com.example.starter.web.dto.Requests.ScheduleRequest;
import com.example.starter.web.dto.Requests.SettleRequest;
import com.example.starter.web.dto.Requests.StartRequest;

/**
 * 引航计划核心服务：状态流转（申请→编排→确认→执行→核销）、工时校验、
 * 资源锁定（确认/改期与锁定同事务）与 requestId 幂等控制。
 *
 * <p>幂等语义：指纹覆盖 requestId 与全部业务字段；同键同参返回首次结果，
 * 同键异参返回 409。幂等记录先于业务写入（同事务），并发下同键请求由主键串行化，
 * 后到者重放首次结果。</p>
 */
@Service
public class PilotPlanService {

    /** 迟到取消临界：执行前 2 小时（恰满 2 小时仍可免费取消）。 */
    static final long CANCEL_NOTICE_MINUTES = 2 * 60;

    private final PlanRepository plans;
    private final IdempotentExecutor idem;
    private final AssignmentValidator assignmentValidator;
    private final Clock clock;

    public PilotPlanService(PlanRepository plans, IdempotentExecutor idem,
                            AssignmentValidator assignmentValidator, Clock clock) {
        this.plans = plans;
        this.idem = idem;
        this.assignmentValidator = assignmentValidator;
        this.clock = clock;
    }

    /** 申请引航计划。 */
    public PlanResponse apply(ApplyRequest request) {
        String fingerprint = IdempotencyRepository.fingerprint("apply", request.requestId(),
                request.applicant(), request.shipName(), request.planStart(), request.planEnd());
        return idem.execute("apply", request.requestId(), fingerprint, () -> {
            validateTimeRange(request.planStart(), request.planEnd());
            long planId = plans.insertPlan(request.requestId(), request.applicant(),
                    request.shipName(), request.planStart(), request.planEnd(), now());
            return toResponse(plans.findById(planId).orElseThrow(), List.of(), false);
        }, PlanResponse.class);
    }

    /** 编排：绑定引航员、拖轮组合与潮水窗口，校验窗口包含关系与工时规则。 */
    public PlanResponse schedule(ScheduleRequest request) {
        String fingerprint = IdempotencyRepository.fingerprint("schedule", request.requestId(),
                request.planId(), request.pilotId(), request.tugIds(),
                request.tideStart(), request.tideEnd());
        return idem.execute("schedule", request.requestId(), fingerprint, () -> {
            PlanRow plan = lockPlan(request.planId());
            requireStatus(plan, PlanStatus.APPLIED, "仅申请状态的计划可编排");
            validateWindow(plan, request.tideStart(), request.tideEnd());
            if (!plans.pilotExists(request.pilotId())) {
                throw ApiException.badRequest("引航员不存在: " + request.pilotId());
            }
            for (Long tugId : request.tugIds()) {
                if (!plans.tugExists(tugId)) {
                    throw ApiException.badRequest("拖轮不存在: " + tugId);
                }
            }
            validatePilotAssignable(request.pilotId(), plan.planStart(), plan.planEnd(), plan.id());
            plans.updateSchedule(plan.id(), request.pilotId(), request.tideStart(), request.tideEnd(),
                    plan.planStart(), plan.planEnd(), now());
            plans.replacePlanTugs(plan.id(), request.tugIds());
            return toResponse(plans.findById(plan.id()).orElseThrow(), request.tugIds(), false);
        }, PlanResponse.class);
    }

    /** 确认：校验资源占用后，引航员与拖轮在同一事务内同时锁定。 */
    public PlanResponse confirm(ConfirmRequest request) {
        String fingerprint = IdempotencyRepository.fingerprint("confirm", request.requestId(),
                request.planId());
        return idem.execute("confirm", request.requestId(), fingerprint, () -> {
            PlanRow plan = lockPlan(request.planId());
            requireStatus(plan, PlanStatus.SCHEDULED, "仅已编排的计划可确认");
            List<Long> tugIds = plans.findTugIds(plan.id());
            if (tugIds.isEmpty()) {
                throw ApiException.conflict("计划未绑定拖轮组合，不可确认");
            }
            lockResources(plan.pilotId(), tugIds);
            checkResourceFree(plan, tugIds);
            plans.insertLock(plan.id(), "PILOT", plan.pilotId(), plan.planStart(), plan.planEnd());
            for (Long tugId : tugIds) {
                plans.insertLock(plan.id(), "TUG", tugId, plan.planStart(), plan.planEnd());
            }
            plans.updateStatus(plan.id(), PlanStatus.CONFIRMED, now());
            return toResponse(plans.findById(plan.id()).orElseThrow(), tugIds, false);
        }, PlanResponse.class);
    }

    /** 开始执行：同一引航员同时刻只允许一条执行中计划。 */
    public PlanResponse start(StartRequest request) {
        String fingerprint = IdempotencyRepository.fingerprint("start", request.requestId(),
                request.planId());
        return idem.execute("start", request.requestId(), fingerprint, () -> {
            PlanRow plan = lockPlan(request.planId());
            requireStatus(plan, PlanStatus.CONFIRMED, "仅已确认的计划可开始执行");
            if (plans.existsExecutingOverlap(plan.pilotId(), plan.planStart(), plan.planEnd(), plan.id())) {
                throw ApiException.conflict("同一引航员同时刻已存在执行中计划");
            }
            plans.updateStatus(plan.id(), PlanStatus.EXECUTING, now());
            return toResponse(plans.findById(plan.id()).orElseThrow(), plans.findTugIds(plan.id()), false);
        }, PlanResponse.class);
    }

    /** 核销：执行结束后释放资源锁；与切换竞争时关闭未结束交班并释放接班预占。 */
    public PlanResponse settle(SettleRequest request) {
        String fingerprint = IdempotencyRepository.fingerprint("settle", request.requestId(),
                request.planId());
        return idem.execute("settle", request.requestId(), fingerprint, () -> {
            PlanRow plan = lockPlan(request.planId());
            requireStatus(plan, PlanStatus.EXECUTING, "仅执行中的计划可核销");
            // 计划行锁串行化核销与切换竞争：核销先提交则交班关闭，不可再切入新引航员
            plans.findOpenHandoverForPlan(plan.id()).ifPresent(handover ->
                    plans.updateHandoverStatus(handover.id(), HandoverStatus.CLOSED,
                            handover.version() + 1, now()));
            plans.deleteLocksByPlanId(plan.id());
            plans.updateStatus(plan.id(), PlanStatus.SETTLED, now());
            return toResponse(plans.findById(plan.id()).orElseThrow(), plans.findTugIds(plan.id()), false);
        }, PlanResponse.class);
    }

    /**
     * 取消：执行前 2 小时外免费取消；恰满 2 小时仍可取消；不足 2 小时
     * 合成违约记录后取消；已到达执行时刻不可取消。
     */
    public PlanResponse cancel(CancelRequest request) {
        String fingerprint = IdempotencyRepository.fingerprint("cancel", request.requestId(),
                request.planId());
        return idem.execute("cancel", request.requestId(), fingerprint, () -> {
            PlanRow plan = lockPlan(request.planId());
            if (plan.status() != PlanStatus.APPLIED && plan.status() != PlanStatus.SCHEDULED
                    && plan.status() != PlanStatus.CONFIRMED) {
                throw ApiException.conflict("当前状态不可取消: " + plan.status());
            }
            if (!now().isBefore(plan.planStart())) {
                throw ApiException.conflict("已到达计划执行时刻，不可取消");
            }
            long leadMinutes = Duration.between(now(), plan.planStart()).toMinutes();
            boolean violation = leadMinutes < CANCEL_NOTICE_MINUTES;
            if (violation) {
                plans.insertViolation(plan.id(), "迟到取消：距执行不足 2 小时", leadMinutes, now());
            }
            plans.deleteLocksByPlanId(plan.id());
            plans.updateStatus(plan.id(), PlanStatus.CANCELLED, now());
            return toResponse(plans.findById(plan.id()).orElseThrow(),
                    plans.findTugIds(plan.id()), violation);
        }, PlanResponse.class);
    }

    /** 改期：重新校验工时与拖轮占用，结论与重新锁定在同一事务内完成。 */
    public PlanResponse reschedule(RescheduleRequest request) {
        String fingerprint = IdempotencyRepository.fingerprint("reschedule", request.requestId(),
                request.planId(), request.planStart(), request.planEnd(),
                request.tideStart(), request.tideEnd());
        return idem.execute("reschedule", request.requestId(), fingerprint, () -> {
            PlanRow plan = lockPlan(request.planId());
            if (plan.status() != PlanStatus.SCHEDULED && plan.status() != PlanStatus.CONFIRMED) {
                throw ApiException.conflict("当前状态不可改期: " + plan.status());
            }
            validateTimeRange(request.planStart(), request.planEnd());
            if (request.planStart().isBefore(request.tideStart())
                    || request.planEnd().isAfter(request.tideEnd())) {
                throw ApiException.badRequest("计划时间须落在潮水窗口内");
            }
            validatePilotAssignable(plan.pilotId(), request.planStart(), request.planEnd(), plan.id());
            List<Long> tugIds = plans.findTugIds(plan.id());
            lockResources(plan.pilotId(), tugIds);
            for (Long tugId : tugIds) {
                if (!plans.findOverlappingLocks("TUG", tugId,
                        request.planStart(), request.planEnd(), plan.id()).isEmpty()) {
                    throw ApiException.conflict("拖轮 " + tugId + " 在改期时段已被占用");
                }
            }
            if (!plans.findOverlappingLocks("PILOT", plan.pilotId(),
                    request.planStart(), request.planEnd(), plan.id()).isEmpty()) {
                throw ApiException.conflict("引航员在改期时段已被锁定");
            }
            // 结论与重新锁定同事务：先释放旧锁，再按新时段一次性锁定
            plans.deleteLocksByPlanId(plan.id());
            if (plan.status() == PlanStatus.CONFIRMED) {
                plans.insertLock(plan.id(), "PILOT", plan.pilotId(),
                        request.planStart(), request.planEnd());
                for (Long tugId : tugIds) {
                    plans.insertLock(plan.id(), "TUG", tugId, request.planStart(), request.planEnd());
                }
            }
            plans.updateReschedule(plan.id(), request.tideStart(), request.tideEnd(),
                    request.planStart(), request.planEnd(), now());
            return toResponse(plans.findById(plan.id()).orElseThrow(), tugIds, false);
        }, PlanResponse.class);
    }

    // ---------- 内部通用逻辑 ----------

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private PlanRow lockPlan(long planId) {
        return plans.findByIdForUpdate(planId)
                .orElseThrow(() -> ApiException.notFound("计划不存在: " + planId));
    }

    private void requireStatus(PlanRow plan, PlanStatus expected, String message) {
        if (plan.status() != expected) {
            throw ApiException.conflict(message + "，当前状态: " + plan.status());
        }
    }

    private void validateTimeRange(LocalDateTime start, LocalDateTime end) {
        if (!start.isBefore(end)) {
            throw ApiException.badRequest("计划开始时间须早于结束时间");
        }
    }

    /** 潮水窗口自身合法且覆盖计划时间（半开区间 [tideStart, tideEnd)）。 */
    private void validateWindow(PlanRow plan, LocalDateTime tideStart, LocalDateTime tideEnd) {
        if (!tideStart.isBefore(tideEnd)) {
            throw ApiException.badRequest("潮水窗口起点须早于终点");
        }
        if (plan.planStart().isBefore(tideStart) || plan.planEnd().isAfter(tideEnd)) {
            throw ApiException.badRequest("计划时间须落在潮水窗口内");
        }
    }

    /** 校验引航员可排：委托给共享的责任段/工时校验组件。 */
    private void validatePilotAssignable(long pilotId, LocalDateTime start, LocalDateTime end,
                                         long excludePlanId) {
        assignmentValidator.validatePilotAssignable(pilotId, start, end, excludePlanId);
    }

    /**
     * 按固定顺序（先引航员后拖轮升序）对资源行加行锁，
     * 串行化并发确认/改期，避免检查与插入之间的占用竞态。
     */
    private void lockResources(long pilotId, List<Long> tugIds) {
        plans.lockPilotRow(pilotId);
        tugIds.stream().sorted().forEach(plans::lockTugRow);
    }

    /** 确认前校验引航员与拖轮组合在计划时段均无其他锁占用（半开区间）。 */
    private void checkResourceFree(PlanRow plan, List<Long> tugIds) {        if (!plans.findOverlappingLocks("PILOT", plan.pilotId(),
                plan.planStart(), plan.planEnd(), plan.id()).isEmpty()) {
            throw ApiException.conflict("引航员在该时段已被锁定");
        }
        for (Long tugId : tugIds) {
            if (!plans.findOverlappingLocks("TUG", tugId,
                    plan.planStart(), plan.planEnd(), plan.id()).isEmpty()) {
                throw ApiException.conflict("拖轮 " + tugId + " 在该时段已被锁定");
            }
        }
    }

    private PlanResponse toResponse(PlanRow plan, List<Long> tugIds, boolean violationCreated) {
        return new PlanResponse(plan.id(), plan.status().name(), plan.pilotId(), tugIds,
                plan.planStart(), plan.planEnd(), plan.tideStart(), plan.tideEnd(), violationCreated);
    }

    /**
     * 计划详情查询：展示各引航员实际责任区间（工时按此分段计算）与最新交班信息
     * （含双方签认意见与接班预占）。未使用交班的计划责任区间与计划时段一致。
     */
    public PlanDetailResponse getDetail(long planId) {
        PlanRow plan = plans.findById(planId)
                .orElseThrow(() -> ApiException.notFound("计划不存在: " + planId));
        List<Long> tugIds = plans.findTugIds(planId);
        List<PlanDetailResponse.ResponsibilitySegment> responsibilities;
        var switched = plans.findSwitchedHandoverForPlan(planId);
        if (switched.isPresent()) {
            // 已切换：出班方责任段截止到交班时刻，接班方承担剩余段
            PlanRepository.HandoverRow handover = switched.get();
            responsibilities = List.of(
                    new PlanDetailResponse.ResponsibilitySegment(
                            handover.outPilotId(), plan.planStart(), handover.handoverAt()),
                    new PlanDetailResponse.ResponsibilitySegment(
                            handover.inPilotId(), handover.handoverAt(), plan.planEnd()));
        } else if (plan.pilotId() != null) {
            responsibilities = List.of(new PlanDetailResponse.ResponsibilitySegment(
                    plan.pilotId(), plan.planStart(), plan.planEnd()));
        } else {
            responsibilities = List.of();
        }
        HandoverResponse handover = plans.findLatestHandoverForPlan(planId)
                .map(row -> HandoverService.toResponse(row, plan))
                .orElse(null);
        return new PlanDetailResponse(plan.id(), plan.status().name(), plan.pilotId(), tugIds,
                plan.planStart(), plan.planEnd(), plan.tideStart(), plan.tideEnd(),
                responsibilities, handover);
    }
}
