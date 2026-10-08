package com.example.starter.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.domain.HandoverStatus;
import com.example.starter.domain.PlanStatus;
import com.example.starter.store.HandoverRepository;
import com.example.starter.store.HandoverRepository.HandoverRow;
import com.example.starter.store.IdempotencyRepository;
import com.example.starter.store.PlanRepository;
import com.example.starter.store.PlanRepository.PlanRow;
import com.example.starter.store.PlanRepository.ResponsibilitySegment;
import com.example.starter.web.ApiException;
import com.example.starter.web.dto.HandoverResponse;
import com.example.starter.web.dto.Requests.HandoverApplyRequest;
import com.example.starter.web.dto.Requests.HandoverCompleteRequest;
import com.example.starter.web.dto.Requests.HandoverConfirmRequest;
import com.example.starter.web.dto.Requests.HandoverWithdrawRequest;

/**
 * 执行中引航交班核心服务。
 *
 * <p>责任语义：交班完成前出班引航员仍负责整段；申请时仅为接班员建立
 * [交班时刻, planEnd) 的接班预占（PILOT 锁），原引航员整段锁与拖轮锁保持不变。
 * 双方分别签认且当前时刻达到约定交班时刻后，在同一事务内原子切换：
 * 出班员锁收窄到交班时刻、接班预占转为剩余段实际占用、计划改挂接班员并推进版本。</p>
 *
 * <p>工时按实际责任段计算（见 {@link PlanRepository#findResponsibilitySegments}），
 * 未使用交班的计划沿用整段旧语义。</p>
 */
@Service
public class HandoverService {

    private final PlanRepository plans;
    private final HandoverRepository handovers;
    private final IdempotencyRepository idem;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public HandoverService(PlanRepository plans, HandoverRepository handovers,
                           IdempotencyRepository idem, TransactionTemplate tx,
                           ObjectMapper objectMapper, Clock clock) {
        this.plans = plans;
        this.handovers = handovers;
        this.idem = idem;
        this.tx = tx;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 交班申请：绑定执行中计划与当前引航员，接班员须不同且满足工时/休息/日累计门槛，
     * 并对 [handoverAt, planEnd) 预占；交班时刻须严格位于计划与潮窗内部（端点拒绝）。
     * 任一资源冲突整单回滚；原引航员占用与拖轮组合保持不变。
     */
    public HandoverResponse apply(HandoverApplyRequest request) {
        // 指纹覆盖完整人员与精确时刻；申请不带版本，版本在切换时按 plan_version 重查
        String fingerprint = IdempotencyRepository.fingerprint("handover-apply", request.requestId(),
                request.planId(), request.fromPilotId(), request.toPilotId(), request.handoverAt());
        return runIdempotent("handover-apply", request.requestId(), () -> fingerprint, () -> {
            PlanRow plan = plans.findByIdForUpdate(request.planId())
                    .orElseThrow(() -> ApiException.notFound("计划不存在: " + request.planId()));
            if (plan.status() != PlanStatus.EXECUTING) {
                throw ApiException.conflict("仅执行中的计划可申请交班，当前状态: " + plan.status());
            }
            if (plan.pilotId() == null || plan.pilotId() != request.fromPilotId()) {
                throw ApiException.conflict("出班引航员必须是当前责任人");
            }
            if (request.toPilotId() == request.fromPilotId()) {
                throw ApiException.badRequest("接班引航员必须与出班引航员不同");
            }
            if (!plans.pilotExists(request.toPilotId())) {
                throw ApiException.badRequest("接班引航员不存在: " + request.toPilotId());
            }
            // 交班时刻严格位于计划区间内部，同时位于潮窗内部，端点拒绝
            LocalDateTime h = request.handoverAt();
            if (!h.isAfter(plan.planStart()) || !h.isBefore(plan.planEnd())) {
                throw ApiException.badRequest("交班时刻须严格位于计划区间内部，端点不允许");
            }
            if (plan.tideStart() != null && plan.tideEnd() != null
                    && (!h.isAfter(plan.tideStart()) || !h.isBefore(plan.tideEnd()))) {
                throw ApiException.badRequest("交班时刻须严格位于潮水窗口内部，端点不允许");
            }
            // 同计划只允许一条未结束交班
            if (handovers.findPendingByPlan(plan.id()).isPresent()) {
                throw ApiException.conflict("该计划已存在未结束的交班单");
            }
            // 本模型按单次交班分割责任段，已完成交班的计划不再受理二次交班
            if (handovers.findSwitchedByPlan(plan.id()).isPresent()) {
                throw ApiException.conflict("该计划已完成交班，不可再次交班");
            }
            // 串行化同一接班员的并发交班/确认，占用与工时任一冲突整单拒绝
            plans.lockPilotRow(request.toPilotId());
            validateSuccessor(request.toPilotId(), h, plan.planEnd(), plan.id());

            long handoverId = handovers.insert(plan.id(), request.fromPilotId(),
                    request.toPilotId(), h, plan.version(), now());
            // 接班预占：仅锁定剩余段；原引航员整段锁与拖轮锁不动
            plans.insertLock(plan.id(), "PILOT", request.toPilotId(), h, plan.planEnd());
            HandoverRow row = handovers.findById(handoverId).orElseThrow();
            return toResponse(row, plan);
        });
    }

    /** 出班/接班引航员分别签认，身份必须对应本人；expectedVersion 过期返回 409。 */
    public HandoverResponse confirm(HandoverConfirmRequest request) {
        String fingerprint = IdempotencyRepository.fingerprint("handover-confirm", request.requestId(),
                request.handoverId(), request.pilotId(), request.comment(), request.expectedVersion());
        return runIdempotent("handover-confirm", request.requestId(), () -> fingerprint, () -> {
            HandoverRow handover = lockHandover(request.handoverId());
            requirePending(handover);
            requireVersion(handover, request.expectedVersion());
            boolean fromSide;
            if (request.pilotId() == handover.fromPilotId()) {
                fromSide = true;
                if (handover.fromConfirmed()) {
                    throw ApiException.conflict("出班引航员已签认，不可重复签认");
                }
            } else if (request.pilotId() == handover.toPilotId()) {
                fromSide = false;
                if (handover.toConfirmed()) {
                    throw ApiException.conflict("接班引航员已签认，不可重复签认");
                }
            } else {
                throw ApiException.conflict("签认人必须是交班双方之一");
            }
            int updated = handovers.confirm(handover.id(), fromSide, request.comment(),
                    request.expectedVersion(), now());
            if (updated == 0) {
                throw ApiException.conflict("交班单状态或版本已变化，请刷新后重试");
            }
            return reload(handover.id());
        });
    }

    /**
     * 原子切换：双方均签认且当前时刻达到约定交班时刻才执行。
     * 切换时重查来源计划版本与接班员工时；出班员责任截止交班时刻，接班员接剩余段。
     */
    public HandoverResponse complete(HandoverCompleteRequest request) {
        String fingerprint = IdempotencyRepository.fingerprint("handover-complete", request.requestId(),
                request.handoverId(), request.expectedVersion());
        return runIdempotent("handover-complete", request.requestId(), () -> fingerprint, () -> {
            HandoverRow handover = lockHandover(request.handoverId());
            requirePending(handover);
            requireVersion(handover, request.expectedVersion());
            if (!handover.fromConfirmed() || !handover.toConfirmed()) {
                throw ApiException.conflict("须交班双方均签认后才可切换");
            }
            if (now().isBefore(handover.handoverAt())) {
                throw ApiException.conflict("当前时刻尚未达到约定交班时刻，不可切换");
            }
            PlanRow plan = lockPlan(handover.planId());
            if (plan.status() != PlanStatus.EXECUTING) {
                throw ApiException.conflict("计划已非执行中，交班不可切换，当前状态: " + plan.status());
            }
            // 首次切换时重查来源计划版本（改期等旧入口改动会使版本过期）
            if (plan.version() != handover.planVersion()) {
                throw ApiException.conflict("来源计划版本已变化，交班切换过期");
            }
            // 首次切换时重查接班员工时（基于其他计划的实际责任段）
            validateSuccessor(handover.toPilotId(), handover.handoverAt(),
                    plan.planEnd(), plan.id());

            // —— 以下写入在同一事务内原子完成 ——
            // 1) 出班员整段锁收窄为 [planStart, handoverAt)
            plans.narrowPilotLockEnd(plan.id(), handover.fromPilotId(), handover.handoverAt());
            // 2) 接班预占锁 [handoverAt, planEnd) 原样保留，转为接班员实际占用；拖轮仍只锁原计划
            // 3) 计划改挂接班员并推进版本（乐观条件：版本一致）
            int switchedPlan = plans.switchPilotIfVersion(plan.id(), handover.toPilotId(),
                    plan.version(), now());
            if (switchedPlan == 0) {
                throw ApiException.conflict("来源计划版本已变化，交班切换过期");
            }
            int switchedHandover = handovers.markSwitched(handover.id(),
                    request.expectedVersion(), now());
            if (switchedHandover == 0) {
                throw ApiException.conflict("交班单状态或版本已变化，请刷新后重试");
            }
            return reload(handover.id());
        });
    }

    /** 切换前撤回：释放接班预占，保留原引航员；已切换不可撤回。 */
    public HandoverResponse withdraw(HandoverWithdrawRequest request) {
        String fingerprint = IdempotencyRepository.fingerprint("handover-withdraw", request.requestId(),
                request.handoverId(), request.pilotId(), request.expectedVersion());
        return runIdempotent("handover-withdraw", request.requestId(), () -> fingerprint, () -> {
            HandoverRow handover = lockHandover(request.handoverId());
            requirePending(handover);
            requireVersion(handover, request.expectedVersion());
            if (request.pilotId() != handover.fromPilotId()) {
                throw ApiException.conflict("仅出班引航员可撤回交班");
            }
            // 先释放接班预占，再关闭交班单；原引航员整段锁与拖轮锁保留
            plans.deleteLocks(handover.planId(), "PILOT", handover.toPilotId());
            int updated = handovers.markWithdrawn(handover.id(), request.expectedVersion(), now());
            if (updated == 0) {
                throw ApiException.conflict("交班单状态或版本已变化，请刷新后重试");
            }
            return reload(handover.id());
        });
    }

    /** 查询交班单：责任区间、双方意见与接班预占。 */
    public HandoverResponse get(long handoverId) {
        HandoverRow handover = handovers.findById(handoverId)
                .orElseThrow(() -> ApiException.notFound("交班单不存在: " + handoverId));
        PlanRow plan = plans.findById(handover.planId())
                .orElseThrow(() -> ApiException.notFound("计划不存在: " + handover.planId()));
        return toResponse(handover, plan);
    }

    /**
     * 查询引航员某自然日的实际责任区间与累计工时（分钟）。
     * 受控计划按交班责任段返回，未交班计划沿用整段。
     */
    public WorkHourView workHours(long pilotId, LocalDate day) {
        LocalDateTime rangeStart = day.atStartOfDay();
        LocalDateTime rangeEnd = day.plusDays(1).atStartOfDay();
        List<ResponsibilitySegment> segments = plans.findResponsibilitySegments(
                pilotId, rangeStart.minusDays(2), rangeEnd.plusDays(2));
        List<WorkHourView.Entry> entries = new ArrayList<>();
        long totalMinutes = 0;
        for (ResponsibilitySegment seg : segments) {
            // 与现有工时规则一致：按责任段开始时刻归属自然日
            if (!seg.start().toLocalDate().equals(day)) {
                continue;
            }
            long minutes = java.time.Duration.between(seg.start(), seg.end()).toMinutes();
            entries.add(new WorkHourView.Entry(seg.planId(), seg.start(), seg.end(), minutes));
            totalMinutes += minutes;
        }
        return new WorkHourView(pilotId, day, totalMinutes,
                entries.stream().sorted(java.util.Comparator.comparing(WorkHourView.Entry::start)).toList());
    }

    /** 工时查询视图。 */
    public record WorkHourView(
            long pilotId,
            LocalDate day,
            long totalMinutes,
            List<Entry> entries) {

        /** 单段实际责任工时。 */
        public record Entry(long planId, LocalDateTime start, LocalDateTime end, long minutes) {
        }
    }

    // ---------- 内部通用逻辑 ----------

    /**
     * 校验接班员在 [start, end) 可承接：与其他计划实际责任段不重叠，
     * 且满足连续作业 4 小时强制休息 1 小时、单日累计不超过 10 小时；
     * 同时检查既有资源锁（含其他交班单的接班预占）不冲突。
     */
    private void validateSuccessor(long toPilotId, LocalDateTime start, LocalDateTime end,
                                   long planId) {
        if (!plans.findOverlappingLocks("PILOT", toPilotId, start, end, planId).isEmpty()) {
            throw ApiException.conflict("接班引航员在交班剩余时段已被占用（含其他接班预占）");
        }
        List<ResponsibilitySegment> others = plans.findResponsibilitySegments(
                toPilotId, start.minusDays(2), end.plusDays(2));
        List<WorkHourValidator.Interval> intervals = new ArrayList<>();
        for (ResponsibilitySegment seg : others) {
            if (seg.planId() == planId) {
                continue;
            }
            if (seg.start().isBefore(end) && seg.end().isAfter(start)) {
                throw ApiException.conflict("接班引航员在交班剩余时段已有计划责任段: " + seg.planId());
            }
            intervals.add(new WorkHourValidator.Interval(seg.start(), seg.end()));
        }
        WorkHourValidator.validate(intervals, new WorkHourValidator.Interval(start, end))
                .ifPresent(message -> {
                    throw ApiException.conflict(message);
                });
    }

    private HandoverResponse reload(long handoverId) {
        HandoverRow row = handovers.findById(handoverId).orElseThrow();
        PlanRow plan = plans.findById(row.planId()).orElseThrow();
        return toResponse(row, plan);
    }

    private HandoverRow lockHandover(long handoverId) {
        return handovers.findByIdForUpdate(handoverId)
                .orElseThrow(() -> ApiException.notFound("交班单不存在: " + handoverId));
    }

    private PlanRow lockPlan(long planId) {
        return plans.findByIdForUpdate(planId)
                .orElseThrow(() -> ApiException.notFound("计划不存在: " + planId));
    }

    private void requirePending(HandoverRow handover) {
        if (handover.status() != HandoverStatus.PENDING) {
            throw ApiException.conflict("交班单已结束，当前状态: " + handover.status());
        }
    }

    private void requireVersion(HandoverRow handover, long expectedVersion) {
        if (handover.version() != expectedVersion) {
            throw ApiException.conflict("交班单版本过期，请刷新后重试（expected="
                    + expectedVersion + ", actual=" + handover.version() + "）");
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /**
     * 幂等执行框架：指纹供应器在事务内、业务锁之后求值；同事务先落幂等记录再执行业务。
     * 同键同参重放首次响应，同键异参 409；业务失败整体回滚不占键。
     */
    private HandoverResponse runIdempotent(String operation, String requestId,
                                           Supplier<String> fingerprintSupplier,
                                           Supplier<HandoverResponse> action) {
        try {
            return tx.execute(status -> {
                String fingerprint = fingerprintSupplier.get();
                var existing = idem.find(requestId);
                if (existing.isPresent()) {
                    return replay(existing.get(), fingerprint);
                }
                idem.insert(requestId, operation, fingerprint, "", now());
                HandoverResponse response = action.get();
                idem.updateResponse(requestId, toJson(response));
                return response;
            });
        } catch (DuplicateKeyException e) {
            var existing = idem.find(requestId);
            if (existing.isPresent()) {
                return replay(existing.get(), fingerprintSupplier.get());
            }
            throw ApiException.conflict("幂等冲突，请重试");
        }
    }

    private HandoverResponse replay(IdempotencyRepository.IdemRow existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId 已存在且业务参数不一致");
        }
        try {
            return objectMapper.readValue(existing.responseBody(), HandoverResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private String toJson(HandoverResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应序列化失败", e);
        }
    }

    private HandoverResponse toResponse(HandoverRow h, PlanRow plan) {
        List<HandoverResponse.Segment> segments = new ArrayList<>();
        boolean preempt = h.status() == HandoverStatus.PENDING;
        if (h.status() == HandoverStatus.SWITCHED) {
            segments.add(new HandoverResponse.Segment(
                    h.fromPilotId(), plan.planStart(), h.handoverAt()));
            segments.add(new HandoverResponse.Segment(
                    h.toPilotId(), h.handoverAt(), plan.planEnd()));
        } else {
            // 未切换（含待签认/已撤回/已关闭）：整段仍归出班引航员
            segments.add(new HandoverResponse.Segment(
                    h.fromPilotId(), plan.planStart(), plan.planEnd()));
        }
        return new HandoverResponse(
                h.id(), h.planId(), h.status().name(), h.fromPilotId(), h.toPilotId(),
                h.handoverAt(), h.fromConfirmed(), h.toConfirmed(),
                h.fromComment(), h.toComment(), h.version(), segments,
                preempt,
                preempt ? h.handoverAt() : null,
                preempt ? plan.planEnd() : null);
    }
}
