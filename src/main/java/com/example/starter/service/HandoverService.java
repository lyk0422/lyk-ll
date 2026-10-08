package com.example.starter.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.example.starter.domain.HandoverStatus;
import com.example.starter.domain.PlanStatus;
import com.example.starter.store.IdempotencyRepository;
import com.example.starter.store.PlanRepository;
import com.example.starter.store.PlanRepository.HandoverRow;
import com.example.starter.store.PlanRepository.PlanRow;
import com.example.starter.web.ApiException;
import com.example.starter.web.dto.HandoverResponse;
import com.example.starter.web.dto.Requests.HandoverAckRequest;
import com.example.starter.web.dto.Requests.HandoverApplyRequest;
import com.example.starter.web.dto.Requests.HandoverWithdrawRequest;

/**
 * 执行中引航交班服务：申请、双方签认与原子切换、撤回。
 *
 * <p>核心规则：</p>
 * <ul>
 *   <li>交班申请绑定执行中计划、当前引航员与不同的接班员，交班时刻严格位于计划
 *       起止之间（端点拒绝）；同计划只一条未结束交班，任一资源冲突整单拒绝；</li>
 *   <li>接班员须满足原工时/休息/日累计门槛，并对 [交班时刻, 计划结束) 预占；
 *       旧引航员原占用在申请后保留，原拖轮组合与潮窗不变；</li>
 *   <li>出班与接班双方分别签认，双方均确认且当前时刻到达约定交班时刻才原子切换；
 *       首次切换时重查工时和来源计划版本；</li>
 *   <li>切换前可撤回（释放接班预占、保留原人），已切换不可撤回；
 *       核销与切换竞争由计划行锁串行化，核销先提交则交班关闭；</li>
 *   <li>交班版本 1 起，修改带 expectedVersion，成功加 1、过期 409；
 *       责任、资源、版本和幂等同事务。</li>
 * </ul>
 */
@Service
public class HandoverService {

    private final PlanRepository plans;
    private final IdempotentExecutor idem;
    private final AssignmentValidator assignmentValidator;
    private final Clock clock;

    public HandoverService(PlanRepository plans, IdempotentExecutor idem,
                           AssignmentValidator assignmentValidator, Clock clock) {
        this.plans = plans;
        this.idem = idem;
        this.assignmentValidator = assignmentValidator;
        this.clock = clock;
    }

    /**
     * 交班申请：校验计划执行中、出班方为当前引航员、接班员不同且存在、
     * 交班时刻严格位于计划内部；校验接班员工时门槛与资源冲突后写入接班预占。
     */
    public HandoverResponse apply(HandoverApplyRequest request) {
        String fingerprint = IdempotencyRepository.fingerprint("handoverApply", request.requestId(),
                request.planId(), request.outPilotId(), request.inPilotId(), request.handoverAt());
        return idem.execute("handoverApply", request.requestId(), fingerprint, () -> {
            PlanRow plan = lockPlan(request.planId());
            if (plan.status() != PlanStatus.EXECUTING) {
                throw ApiException.conflict("仅执行中的计划可发起交班，当前状态: " + plan.status());
            }
            if (plan.pilotId() == null || !plan.pilotId().equals(request.outPilotId())) {
                throw ApiException.badRequest("出班引航员与计划当前负责引航员不一致");
            }
            if (request.inPilotId().equals(request.outPilotId())) {
                throw ApiException.badRequest("接班员须与出班员不同");
            }
            if (!plans.pilotExists(request.inPilotId())) {
                throw ApiException.badRequest("引航员不存在: " + request.inPilotId());
            }
            // 交班时刻严格位于计划起止之间（端点拒绝）；计划位于潮窗内，故该时刻亦在潮窗内部
            if (!request.handoverAt().isAfter(plan.planStart())
                    || !request.handoverAt().isBefore(plan.planEnd())) {
                throw ApiException.badRequest("交班时刻须严格位于计划起止之间，端点拒绝");
            }
            if (plans.findOpenHandoverForPlan(plan.id()).isPresent()) {
                throw ApiException.conflict("该计划已存在未结束交班");
            }
            // 接班员须满足原工时、休息和日累计门槛（按 [交班时刻, 计划结束) 责任段）
            assignmentValidator.validatePilotAssignable(request.inPilotId(),
                    request.handoverAt(), plan.planEnd(), plan.id());
            // 任一资源冲突整单拒绝：接班员在交班时段不得已被其他计划锁定
            plans.lockPilotRow(request.inPilotId());
            if (!plans.findOverlappingLocks("PILOT", request.inPilotId(),
                    request.handoverAt(), plan.planEnd(), plan.id()).isEmpty()) {
                throw ApiException.conflict("接班员在交班时段已被其他计划锁定");
            }
            long handoverId = plans.insertHandover(plan.id(), request.requestId(),
                    request.outPilotId(), request.inPilotId(), request.handoverAt(),
                    plan.version(), now());
            // 接班预占与申请同事务；旧引航员原占用保留，拖轮锁不变
            plans.insertLock(plan.id(), "PILOT", request.inPilotId(),
                    request.handoverAt(), plan.planEnd());
            return toResponse(plans.findHandoverById(handoverId).orElseThrow(),
                    plans.findById(plan.id()).orElseThrow());
        }, HandoverResponse.class);
    }

    /**
     * 签认：身份须为交班双方之一；双方均确认且当前时刻到达约定交班时刻才原子切换。
     * 已签认方重复签认不重复计版本，仅在满足切换条件时触发切换。
     */
    public HandoverResponse ack(HandoverAckRequest request) {
        String fingerprint = IdempotencyRepository.fingerprint("handoverAck", request.requestId(),
                request.handoverId(), request.pilotId(), request.comment(), request.expectedVersion());
        return idem.execute("handoverAck", request.requestId(), fingerprint, () -> {
            HandoverRow found = plans.findHandoverById(request.handoverId())
                    .orElseThrow(() -> ApiException.notFound("交班不存在: " + request.handoverId()));
            // 先锁计划再锁交班，与核销保持一致的加锁顺序，串行化切换/核销竞争
            PlanRow plan = lockPlan(found.planId());
            HandoverRow handover = plans.findHandoverByIdForUpdate(found.id()).orElseThrow();
            requireOpen(handover);
            requireVersion(handover, request.expectedVersion());
            boolean isOut = request.pilotId() == handover.outPilotId();
            boolean isIn = request.pilotId() == handover.inPilotId();
            if (!isOut && !isIn) {
                throw ApiException.badRequest("签认人须为交班双方引航员");
            }
            if ((isOut && !handover.outAck()) || (isIn && !handover.inAck())) {
                plans.updateHandoverAck(handover.id(), isOut ? "OUT" : "IN",
                        request.comment(), handover.version() + 1, now());
                handover = plans.findHandoverById(handover.id()).orElseThrow();
            }
            if (handover.outAck() && handover.inAck() && !now().isBefore(handover.handoverAt())) {
                return switchNow(handover, plan);
            }
            return toResponse(handover, plan);
        }, HandoverResponse.class);
    }

    /** 撤回：仅切换前可撤回，释放接班预占而保留原引航员；已切换不能撤回。 */
    public HandoverResponse withdraw(HandoverWithdrawRequest request) {
        String fingerprint = IdempotencyRepository.fingerprint("handoverWithdraw",
                request.requestId(), request.handoverId(), request.expectedVersion());
        return idem.execute("handoverWithdraw", request.requestId(), fingerprint, () -> {
            HandoverRow found = plans.findHandoverById(request.handoverId())
                    .orElseThrow(() -> ApiException.notFound("交班不存在: " + request.handoverId()));
            PlanRow plan = lockPlan(found.planId());
            HandoverRow handover = plans.findHandoverByIdForUpdate(found.id()).orElseThrow();
            requireVersion(handover, request.expectedVersion());
            if (handover.status() == HandoverStatus.SWITCHED) {
                throw ApiException.conflict("交班已切换，不可撤回");
            }
            requireOpen(handover);
            // 释放接班预占，保留原引航员占用
            plans.deletePilotLock(plan.id(), handover.inPilotId());
            plans.updateHandoverStatus(handover.id(), HandoverStatus.WITHDRAWN,
                    handover.version() + 1, now());
            return toResponse(plans.findHandoverById(handover.id()).orElseThrow(), plan);
        }, HandoverResponse.class);
    }

    // ---------- 内部通用逻辑 ----------

    /**
     * 原子切换（与签认同事务）：首次切换时重查接班员工时与来源计划版本；
     * 出班方责任段截止到交班时刻，接班方承担剩余段，拖轮锁保持原计划不变。
     */
    private HandoverResponse switchNow(HandoverRow handover, PlanRow plan) {
        if (plan.status() != PlanStatus.EXECUTING) {
            throw ApiException.conflict("来源计划已不在执行中，不可切换: " + plan.status());
        }
        if (plan.version() != handover.planVersion()) {
            throw ApiException.conflict("来源计划版本已变更，交班申请已过期");
        }
        // 首次切换时重查工时：申请到切换之间接班员责任可能变化
        assignmentValidator.validatePilotAssignable(handover.inPilotId(),
                handover.handoverAt(), plan.planEnd(), plan.id());
        // 出班方占用截断到交班时刻；接班预占 [交班时刻, 计划结束) 转为正式占用
        plans.truncatePilotLock(plan.id(), handover.outPilotId(), handover.handoverAt());
        plans.updatePlanPilot(plan.id(), handover.inPilotId(), now());
        plans.updateHandoverStatus(handover.id(), HandoverStatus.SWITCHED,
                handover.version() + 1, now());
        return toResponse(plans.findHandoverById(handover.id()).orElseThrow(),
                plans.findById(plan.id()).orElseThrow());
    }

    private void requireOpen(HandoverRow handover) {
        if (handover.status() != HandoverStatus.OPEN) {
            throw ApiException.conflict("交班已结束，不可操作: " + handover.status());
        }
    }

    private void requireVersion(HandoverRow handover, long expectedVersion) {
        if (handover.version() != expectedVersion) {
            throw ApiException.conflict("交班版本已过期，当前版本: " + handover.version());
        }
    }

    private PlanRow lockPlan(long planId) {
        return plans.findByIdForUpdate(planId)
                .orElseThrow(() -> ApiException.notFound("计划不存在: " + planId));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** 交班视图：OPEN 状态展示接班预占区间，其余状态预占已转正或释放。 */
    static HandoverResponse toResponse(HandoverRow handover, PlanRow plan) {
        boolean open = handover.status() == HandoverStatus.OPEN;
        return new HandoverResponse(handover.id(), handover.planId(), handover.status().name(),
                handover.outPilotId(), handover.inPilotId(), handover.handoverAt(),
                handover.outAck(), handover.outComment(), handover.inAck(), handover.inComment(),
                open ? handover.handoverAt() : null,
                open ? plan.planEnd() : null,
                handover.version());
    }
}
