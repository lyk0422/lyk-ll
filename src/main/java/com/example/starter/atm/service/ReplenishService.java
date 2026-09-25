package com.example.starter.atm.service;

import com.example.starter.atm.domain.*;
import com.example.starter.atm.repo.*;
import com.example.starter.atm.support.DomainClock;
import com.example.starter.atm.support.UtcDates;
import com.example.starter.atm.web.ConflictException;
import com.example.starter.atm.web.ForbiddenException;
import com.example.starter.atm.web.NotFoundException;
import com.example.starter.atm.web.UnprocessableException;
import com.example.starter.atm.web.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * ATM 加钞计划领域服务：
 * 预测编制 -> 审批 -> 双人押运打卡 -> 双人清点 -> 设备确认（三方对账核销/挂账） -> 销账 -> 日结封存。
 * 金额单位：分；所有日界按 UTC；时间为 epoch 毫秒。
 */
@Service
public class ReplenishService {

    /** 押运时限：审批通过后 4 小时（毫秒）。 */
    static final long ESCORT_WINDOW_MILLIS = 4L * 60 * 60 * 1000;
    /** 长短款销账期限：3 个自然日（UTC 日界，恰第 3 日受理合法）。 */
    static final int OVER_SHORT_SETTLE_DAYS = 3;

    private final PlanRepository planRepository;
    private final AtmRepository atmRepository;
    private final CashBoxRepository boxRepository;
    private final GuardRepository guardRepository;
    private final CheckinRepository checkinRepository;
    private final OverShortRepository overShortRepository;
    private final PlanEventRepository eventRepository;
    private final IdempotencyService idempotency;
    private final DomainClock clock;
    private final TransactionTemplate txRequiresNew;

    public ReplenishService(PlanRepository planRepository,
                          AtmRepository atmRepository,
                          CashBoxRepository boxRepository,
                          GuardRepository guardRepository,
                          CheckinRepository checkinRepository,
                          OverShortRepository overShortRepository,
                          PlanEventRepository eventRepository,
                          IdempotencyService idempotency,
                          DomainClock clock,
                          PlatformTransactionManager transactionManager) {
        this.planRepository = planRepository;
        this.atmRepository = atmRepository;
        this.boxRepository = boxRepository;
        this.guardRepository = guardRepository;
        this.checkinRepository = checkinRepository;
        this.overShortRepository = overShortRepository;
        this.eventRepository = eventRepository;
        this.idempotency = idempotency;
        this.clock = clock;
        this.txRequiresNew = new TransactionTemplate(transactionManager);
        this.txRequiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ============================ 预测编制 ============================

    @Transactional
    public PlanResponse prepare(PreparePlanRequest req) {
        String fingerprint = fingerprint("plan.prepare", req.requestId(), req.atmNo(),
                req.boxNo(), String.valueOf(req.plannedAmount()));
        Object body = idempotency.execute(req.requestId(), "plan.prepare", fingerprint,
                PlanResponse.class, () -> doPrepare(req)).body();
        return (PlanResponse) body;
    }

    private PlanResponse doPrepare(PreparePlanRequest req) {
        Atm atm = atmRepository.findByNo(req.atmNo());
        if (atm == null) {
            throw new NotFoundException("ATM 不存在: " + req.atmNo());
        }
        CashBox box = boxRepository.findByNo(req.boxNo());
        if (box == null) {
            throw new NotFoundException("款箱不存在: " + req.boxNo());
        }
        if (box.atmId() != atm.id()) {
            throw new UnprocessableException("款箱不属于该 ATM");
        }
        if (atm.escortFrozen()) {
            throw new ForbiddenException("该 ATM 已因押运异常冻结，禁止编制新计划");
        }
        if (!"AVAILABLE".equals(box.status())) {
            throw new ConflictException("款箱已被占用: " + req.boxNo());
        }
        long now = clock.nowMillis();
        long predicted = atm.avgDailyWithdrawal();
        long planned = req.plannedAmount() != null ? req.plannedAmount() : predicted;
        if (planned <= 0) {
            throw new UnprocessableException("加钞额必须为正数");
        }
        // 单笔加钞不得超过钞箱容量，恰等容量合法
        if (planned > Math.min(atm.cassetteCapacity(), box.capacity())) {
            throw new UnprocessableException("加钞额超过钞箱容量");
        }
        String utcDate = UtcDates.dateOf(now);

        ReplenishPlan plan = new ReplenishPlan();
        plan.setPlanNo(generatePlanNo(atm.atmNo(), now));
        plan.setRequestId(req.requestId());
        plan.setAtmId(atm.id());
        plan.setBoxId(box.id());
        plan.setPlanUtcDate(utcDate);
        plan.setPredictedAmount(predicted);
        plan.setPlannedAmount(planned);
        plan.setStatus(PlanStatus.PREPARED);
        plan.setCreatedAt(now);
        plan.setUpdatedAt(now);
        try {
            planRepository.insert(plan);
        } catch (org.springframework.dao.DataAccessException e) {
            // (atm_id, plan_utc_date) 唯一索引：同一 ATM 同一 UTC 日并发编制只唯一受理
            throw new ConflictException("该 ATM 当日（UTC）已存在执行中的加钞计划");
        }
        boxRepository.markLoaded(box.id(), planned);
        return toPlanResponse(planRepository.findByPlanNo(plan.getPlanNo()));
    }

    // ============================ 审批 ============================

    @Transactional
    public PlanResponse approve(String planNo, ApproveRequest req) {
        String fingerprint = fingerprint("plan.approve", req.requestId(), planNo);
        Object body = idempotency.execute(req.requestId(), "plan.approve", fingerprint,
                PlanResponse.class, () -> doApprove(planNo)).body();
        return (PlanResponse) body;
    }

    private PlanResponse doApprove(String planNo) {
        ReplenishPlan plan = requirePlan(planNo);
        if (plan.getStatus() != PlanStatus.PREPARED) {
            throw new ConflictException("仅编制完成的计划可审批，当前状态: " + plan.getStatus());
        }
        long now = clock.nowMillis();
        plan.setApprovedAt(now);
        // 押运时限 = 审批通过时刻 + 4 小时
        plan.setEscortDeadline(now + ESCORT_WINDOW_MILLIS);
        plan.setStatus(PlanStatus.APPROVED);
        plan.setUpdatedAt(now);
        commitUpdate(plan);
        return toPlanResponse(plan);
    }

    // ============================ 押运打卡 ============================

    @Transactional
    public CheckinResponse checkin(String planNo, CheckinRequest req) {
        String fingerprint = fingerprint("plan.checkin", req.requestId(), planNo, req.guardNo());
        Object body = idempotency.execute(req.requestId(), "plan.checkin", fingerprint,
                CheckinResponse.class, () -> doCheckin(planNo, req.guardNo())).body();
        return (CheckinResponse) body;
    }

    private CheckinResponse doCheckin(String planNo, String guardNo) {
        ReplenishPlan plan = requirePlan(planNo);
        long now = clock.nowMillis();
        // 封存为终态：迟到打卡只登记迟到事件
        if (plan.getStatus() == PlanStatus.SEALED) {
            eventRepository.insert(plan.getId(), "LATE_CHECKIN", true,
                    "封存后押运打卡迟到: guard=" + guardNo, now, now);
            return new CheckinResponse(planNo, guardNo, 0,
                    checkinRepository.findByPlanId(plan.getId()).size(), false, "SEALED", true);
        }
        Guard guard = guardRepository.findByNo(guardNo);
        if (guard == null) {
            throw new NotFoundException("押运员不存在: " + guardNo);
        }
        if (guard.frozen()) {
            throw new ForbiddenException("押运员已被冻结，禁止接新押运任务: " + guardNo);
        }
        if (plan.getStatus() == PlanStatus.ESCORT_ABNORMAL) {
            throw new UnprocessableException("押运已异常，不能继续打卡");
        }
        if (plan.getStatus() != PlanStatus.APPROVED && plan.getStatus() != PlanStatus.IN_TRANSIT) {
            throw new ConflictException("当前状态不可押运打卡: " + plan.getStatus());
        }
        // 恰满 4 小时未完成双人打卡：押运异常并冻结该 ATM 后续计划（独立事务先落库，再拒绝本次打卡）
        if (now >= plan.getEscortDeadline()) {
            txRequiresNew.executeWithoutResult(txStatus -> {
                markEscortAbnormal(planRepository.findById(plan.getId()), clock.nowMillis());
            });
            throw new UnprocessableException("押运时限已到（恰满/超过 4 小时），计划转押运异常");
        }
        List<EscortCheckin> existing = checkinRepository.findByPlanId(plan.getId());
        if (existing.stream().anyMatch(c -> c.guardId() == guard.id())) {
            // 同人重复打卡拒绝（唯一索引同时兜底）
            throw new ConflictException("同一押运员不得重复打卡: " + guardNo);
        }
        if (existing.size() >= 2) {
            throw new ConflictException("双人打卡已完成");
        }
        int seq = existing.size() + 1;
        try {
            checkinRepository.insert(plan.getId(), guard.id(), seq, now, now);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new ConflictException("同一押运员不得重复打卡: " + guardNo);
        }
        if (seq == 1) {
            plan.setStatus(PlanStatus.IN_TRANSIT);
        } else {
            // 两名不同押运员先后打卡齐备
            plan.setStatus(PlanStatus.ESCORT_DONE);
        }
        plan.setUpdatedAt(now);
        commitUpdate(plan);
        return new CheckinResponse(planNo, guardNo, seq, seq,
                seq == 2, plan.getStatus().name(), false);
    }

    // ============================ 双人清点 ============================

    @Transactional
    public PlanResponse count(String planNo, CountRequest req) {
        String fingerprint = fingerprint("plan.count", req.requestId(), planNo,
                req.guard1No(), req.guard2No(), String.valueOf(req.countedAmount()));
        Object body = idempotency.execute(req.requestId(), "plan.count", fingerprint,
                PlanResponse.class, () -> doCount(planNo, req)).body();
        return (PlanResponse) body;
    }

    private PlanResponse doCount(String planNo, CountRequest req) {
        ReplenishPlan plan = requirePlan(planNo);
        long now = clock.nowMillis();
        if (plan.getStatus() == PlanStatus.SEALED) {
            eventRepository.insert(plan.getId(), "LATE_COUNT", true,
                    "封存后清点数据迟到: amount=" + req.countedAmount(), now, now);
            return toPlanResponse(plan);
        }
        if (req.guard1No().equals(req.guard2No())) {
            throw new UnprocessableException("双人清点必须是两名不同的押运员");
        }
        Guard g1 = guardRepository.findByNo(req.guard1No());
        Guard g2 = guardRepository.findByNo(req.guard2No());
        if (g1 == null || g2 == null) {
            throw new NotFoundException("清点押运员不存在");
        }
        if (plan.getStatus() != PlanStatus.ESCORT_DONE) {
            throw new ConflictException("仅双人押运打卡完成后可清点，当前状态: " + plan.getStatus());
        }
        if (req.countedAmount() <= 0) {
            throw new UnprocessableException("清点数必须为正数");
        }
        plan.setCountedAmount(req.countedAmount());
        plan.setCountedAt(now);
        plan.setCountGuard1Id(g1.id());
        plan.setCountGuard2Id(g2.id());
        plan.setStatus(PlanStatus.COUNTED);
        plan.setUpdatedAt(now);
        commitUpdate(plan);
        return toPlanResponse(plan);
    }

    // ==================== 设备确认：三方对账核销 / 长短款挂账 ====================

    @Transactional
    public PlanResponse deviceConfirm(String planNo, DeviceConfirmRequest req) {
        String fingerprint = fingerprint("plan.deviceConfirm", req.requestId(), planNo,
                String.valueOf(req.deviceAmount()));
        Object body = idempotency.execute(req.requestId(), "plan.deviceConfirm", fingerprint,
                PlanResponse.class, () -> doDeviceConfirm(planNo, req.deviceAmount())).body();
        return (PlanResponse) body;
    }

    private PlanResponse doDeviceConfirm(String planNo, long deviceAmount) {
        ReplenishPlan plan = requirePlan(planNo);
        long now = clock.nowMillis();
        if (plan.getStatus() == PlanStatus.SEALED) {
            eventRepository.insert(plan.getId(), "LATE_DEVICE_CONFIRM", true,
                    "封存后设备确认迟到: amount=" + deviceAmount, now, now);
            return toPlanResponse(plan);
        }
        if (plan.getStatus() != PlanStatus.COUNTED) {
            throw new ConflictException("仅双人清点完成后可设备确认，当前状态: " + plan.getStatus());
        }
        if (deviceAmount <= 0) {
            throw new UnprocessableException("设备入账数必须为正数");
        }
        long counted = plan.getCountedAmount();
        long planned = plan.getPlannedAmount();
        plan.setDeviceAmount(deviceAmount);
        plan.setDeviceConfirmedAt(now);

        if (deviceAmount == counted && counted == planned) {
            // 押运员清点数、设备入账数与计划数三方一致方可核销
            plan.setStatus(PlanStatus.RECONCILED);
            plan.setReconciledAt(now);
            plan.setUpdatedAt(now);
            commitUpdate(plan);
            return toPlanResponse(plan);
        }

        // 任两方不一致转长短款；挂账金额 = 实际 - 计划；挂账与核销状态同事务提交
        long diff = deviceAmount - planned;
        String occurredDate = UtcDates.dateOf(now);
        String deadlineDate = UtcDates.plusDays(occurredDate, OVER_SHORT_SETTLE_DAYS);
        OverShort os = new OverShort(
                null, plan.getId(),
                diff > 0 ? "LONG" : "SHORT",
                diff, planned, deviceAmount,
                OverShortStatus.OPEN.name(), now, deadlineDate,
                null, null, null, now, now);
        long osId = overShortRepository.insert(os);
        plan.setOverShortId(osId);
        plan.setStatus(PlanStatus.OVER_SHORT);
        plan.setUpdatedAt(now);
        commitUpdate(plan);
        return toPlanResponse(plan);
    }

    // ==================== 长短款销账（3 个自然日，UTC 日界） ====================

    @Transactional
    public PlanResponse settleOverShort(String planNo, SettleOverShortRequest req) {
        String fingerprint = fingerprint("plan.overShort.settle", req.requestId(), planNo);
        Object body = idempotency.execute(req.requestId(), "plan.overShort.settle", fingerprint,
                PlanResponse.class, () -> doSettleOverShort(planNo, req.requestId())).body();
        return (PlanResponse) body;
    }

    private PlanResponse doSettleOverShort(String planNo, String requestId) {
        ReplenishPlan plan = requirePlan(planNo);
        if (plan.getOverShortId() == null) {
            throw new NotFoundException("该计划无长短款挂账: " + planNo);
        }
        OverShort os = overShortRepository.findById(plan.getOverShortId());
        if (os == null) {
            throw new NotFoundException("长短款挂账记录不存在");
        }
        long now = clock.nowMillis();
        String today = UtcDates.dateOf(now);
        if (os.status().equals(OverShortStatus.SETTLED.name())) {
            // 已销账：原销账请求可幂等重放，其他 requestId 拒绝
            if (requestId.equals(os.settleRequestId())) {
                return toPlanResponse(plan);
            }
            throw new ConflictException("挂账已销账");
        }
        if (os.status().equals(OverShortStatus.ESCALATED.name())) {
            throw new ConflictException("挂账已逾期升级，不能销账");
        }
        // 恰第 3 日（UTC 日界）提交受理合法；超过截止日则逾期（独立事务完成升级再拒绝）
        if (today.compareTo(os.deadlineUtcDate()) > 0) {
            txRequiresNew.executeWithoutResult(txStatus -> {
                OverShort fresh = overShortRepository.findById(os.id());
                ReplenishPlan freshPlan = planRepository.findById(plan.getId());
                escalateOverShort(freshPlan, fresh, clock.nowMillis());
            });
            throw new UnprocessableException("长短款超过 3 个自然日销账期限，已升级并冻结责任人新任务");
        }
        int rows = overShortRepository.markSettled(os.id(), requestId, now);
        if (rows == 0) {
            throw new ConflictException("挂账并发状态变更，销账失败");
        }
        plan.setStatus(PlanStatus.OVER_SHORT_SETTLED);
        plan.setUpdatedAt(now);
        commitUpdate(plan);
        return toPlanResponse(plan);
    }

    /** 逾期升级：挂账置 ESCALATED，冻结两名责任人新押运打卡任务，记录事件。 */
    private void escalateOverShort(ReplenishPlan plan, OverShort os, long now) {
        if (overShortRepository.markEscalated(os.id(), now) == 1) {
            if (plan.getCountGuard1Id() != null) {
                guardRepository.markFrozen(plan.getCountGuard1Id());
            }
            if (plan.getCountGuard2Id() != null) {
                guardRepository.markFrozen(plan.getCountGuard2Id());
            }
            eventRepository.insert(plan.getId(), "OVERSHORT_ESCALATED", false,
                    "长短款逾期未销账，责任人已冻结，挂账 id=" + os.id(), now, now);
        }
    }

    // ==================== 日结封存（终态） ====================

    @Transactional
    public PlanResponse seal(String planNo, SealRequest req) {
        String fingerprint = fingerprint("plan.seal", req.requestId(), planNo);
        Object body = idempotency.execute(req.requestId(), "plan.seal", fingerprint,
                PlanResponse.class, () -> doSeal(planNo)).body();
        return (PlanResponse) body;
    }

    private PlanResponse doSeal(String planNo) {
        ReplenishPlan plan = requirePlan(planNo);
        long now = clock.nowMillis();
        // 重复封存返回幂等成功（终态不再变化）
        if (plan.getStatus() == PlanStatus.SEALED) {
            return toPlanResponse(plan);
        }
        boolean sealable = plan.getStatus() == PlanStatus.RECONCILED
                || plan.getStatus() == PlanStatus.OVER_SHORT_SETTLED;
        if (!sealable) {
            throw new ConflictException("仅核销完成或长短款已销账的计划可封存，当前状态: " + plan.getStatus());
        }
        plan.setStatus(PlanStatus.SEALED);
        plan.setSealedAt(now);
        plan.setUpdatedAt(now);
        commitUpdate(plan);
        return toPlanResponse(plan);
    }

    // ==================== 押运 4 小时超时扫描 ====================

    /**
     * 恰满 4 小时仍未完成双人打卡的在途计划转押运异常并冻结该 ATM 后续计划。
     * 返回本次转为异常的计划数。
     */
    @Transactional
    public int sweepEscortTimeouts() {
        long now = clock.nowMillis();
        int count = 0;
        for (ReplenishPlan plan : planRepository.findEscortTimedOut(now)) {
            markEscortAbnormal(plan, now);
            count++;
        }
        return count;
    }

    private void markEscortAbnormal(ReplenishPlan plan, long now) {
        plan.setStatus(PlanStatus.ESCORT_ABNORMAL);
        plan.setUpdatedAt(now);
        commitUpdate(plan);
        atmRepository.markFrozen(plan.getAtmId());
        eventRepository.insert(plan.getId(), "ESCORT_ABNORMAL", false,
                "押运时限（审批通过+4 小时）恰满/超过仍未完成双人打卡", now, now);
    }

    // ==================== 查询（台账/对账/挂账链复算） ====================

    @Transactional(readOnly = true)
    public PlanResponse getPlan(String planNo) {
        return toPlanResponse(requirePlan(planNo));
    }

    @Transactional(readOnly = true)
    public List<PlanResponse> ledger() {
        List<PlanResponse> out = new ArrayList<>();
        for (ReplenishPlan p : planRepository.findAll()) {
            out.add(toPlanResponse(p));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<OverShortResponse> overShorts() {
        List<OverShortResponse> out = new ArrayList<>();
        for (OverShort o : overShortRepository.findAll()) {
            ReplenishPlan p = planRepository.findById(o.planId());
            out.add(new OverShortResponse(o.id(), p == null ? null : p.getPlanNo(),
                    o.direction(), o.amount(), o.plannedAmount(), o.actualAmount(),
                    o.status(), o.occurredAt(), o.deadlineUtcDate(),
                    o.settledAt(), o.escalatedAt()));
        }
        return out;
    }

    // ==================== 辅助方法 ====================

    private ReplenishPlan requirePlan(String planNo) {
        ReplenishPlan plan = planRepository.findByPlanNo(planNo);
        if (plan == null) {
            throw new NotFoundException("加钞计划不存在: " + planNo);
        }
        return plan;
    }

    private void commitUpdate(ReplenishPlan plan) {
        if (planRepository.update(plan) == 0) {
            throw new ConflictException("计划并发更新冲突，请重试");
        }
    }

    private PlanResponse toPlanResponse(ReplenishPlan p) {
        Atm atm = atmRepository.findById(p.getAtmId());
        CashBox box = boxRepository.findById(p.getBoxId());
        String g1 = p.getCountGuard1Id() == null ? null
                : guardNo(p.getCountGuard1Id());
        String g2 = p.getCountGuard2Id() == null ? null
                : guardNo(p.getCountGuard2Id());
        boolean late = p.getStatus() == PlanStatus.SEALED
                && eventRepository.findByPlanId(p.getId()).stream().anyMatch(PlanEvent::late);
        return PlanResponse.of(p,
                atm == null ? null : atm.atmNo(),
                box == null ? null : box.boxNo(), g1, g2, late);
    }

    private String guardNo(long guardId) {
        Guard g = guardRepository.findById(guardId);
        return g == null ? null : g.guardNo();
    }

    private static final java.util.concurrent.atomic.AtomicLong PLAN_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    private String generatePlanNo(String atmNo, long now) {
        return "PL-" + atmNo + "-" + Long.toString(now, 36)
                + "-" + Long.toString(PLAN_SEQ.incrementAndGet() & 0xffffL, 36);
    }

    /** 业务指纹：覆盖 requestId 与全部业务字段（含端点与路径单号），SHA-256。 */
    static String fingerprint(String endpoint, String... parts) {
        String joined = endpoint + "|" + String.join("|", parts);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
