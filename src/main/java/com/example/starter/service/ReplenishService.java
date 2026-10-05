package com.example.starter.service;

import com.example.starter.api.ApproveRequest;
import com.example.starter.api.CheckinRequest;
import com.example.starter.api.CountRequest;
import com.example.starter.api.CreatePlanRequest;
import com.example.starter.api.DailyReconciliationResponse;
import com.example.starter.api.DeviceConfirmRequest;
import com.example.starter.api.DiscrepancyResponse;
import com.example.starter.api.FastTrackAuditResponse;
import com.example.starter.api.ForecastGenerateRequest;
import com.example.starter.api.ForecastResponse;
import com.example.starter.api.PlanResponse;
import com.example.starter.api.ReconciliationResponse;
import com.example.starter.api.ResumeRequest;
import com.example.starter.api.SealRequest;
import com.example.starter.api.SettleDiscrepancyRequest;
import com.example.starter.domain.DiscrepancyStatus;
import com.example.starter.domain.PlanStatus;
import com.example.starter.domain.PlanType;
import com.example.starter.repo.Atm;
import com.example.starter.repo.CheckinRow;
import com.example.starter.repo.Custodian;
import com.example.starter.repo.DiscrepancyRow;
import com.example.starter.repo.HolidayRow;
import com.example.starter.repo.IdempotentRow;
import com.example.starter.repo.PlanRow;
import com.example.starter.repo.ReplenishRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * ATM 加钞计划与双人押运核销核心服务。
 *
 * <p>流程：预测编制 DRAFT → 审批 APPROVED（起算 4 小时押运时限）→ 两名不同押运员先后打卡后 ESCORTED
 * → 双人清点 COUNTED → 设备确认并三方核销 RECONCILED（不一致转 DISCREPANCY 同事务挂账）→ 日结封存 SEALED。
 * 押运恰满 4 小时（临时计划 2 小时）未完成双人打卡转 ESCORT_ABNORMAL 并冻结该 ATM；
 * 长短款须 3 个自然日（UTC 日界）内销账，恰第 3 日受理，逾期升级并冻结责任人。
 * 节假日临时计划：按合成日历预测节前 3 日峰值，超日均 150% 阈值的网点自动生成，
 * 快速通道免逐级审批（生成即放行，押运时限 2 小时），与在途计划冲突按金额大者优先、
 * 被让路计划保留排队位置（YIELDED，可恢复）；快速通道结论留痕并纳入日结核对。
 * 同一 ATM 同时刻只允许一张执行中（APPROVED/ESCORTED/COUNTED）加钞单。
 * 全部写操作以 requestId 幂等：指纹覆盖 requestId 与全部业务字段。
 */
@Service
public class ReplenishService {

    /** 押运时限：常规计划审批通过后 4 小时。 */
    static final long ESCORT_LIMIT_HOURS = 4;
    /** 押运时限：临时计划快速放行后 2 小时。 */
    static final long TEMP_ESCORT_LIMIT_HOURS = 2;
    /** 节假日峰值阈值（基点）：预测峰值须严格超出日均取现的 150% 才生成临时计划。 */
    static final long PEAK_THRESHOLD_BP = 150;
    /** 长短款销账期限：3 个自然日（UTC 日界），恰第 3 日提交仍受理。 */
    static final long DISCREPANCY_DUE_DAYS = 3;

    private final ReplenishRepository repo;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public ReplenishService(ReplenishRepository repo, Clock clock, ObjectMapper objectMapper) {
        this.repo = repo;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /** 服务动作结果：statusCode 为首次受理时的 HTTP 状态，body 为响应体。 */
    public record Outcome(int statusCode, Object body) {
        static Outcome ok(Object body) {
            return new Outcome(200, body);
        }
    }

    // ==================== 预测编制 ====================

    @Transactional
    public Outcome createPlan(CreatePlanRequest req) {
        String fingerprint = fingerprint("CREATE_PLAN", req.requestId(), req.atmCode(), String.valueOf(req.amount()));
        return withIdempotency(req.requestId(), "CREATE_PLAN", fingerprint, null, () -> {
            Atm atm = repo.findAtmByCode(req.atmCode())
                    .orElseThrow(() -> new BusinessException(404, "ATM 不存在: " + req.atmCode()));
            if (atm.frozen()) {
                throw new BusinessException(409, "该 ATM 已因押运异常冻结，不受理新加钞计划: " + atm.code());
            }
            Instant now = Instant.now(clock);
            LocalDate businessDate = LocalDate.ofInstant(now, ZoneOffset.UTC);
            long predicted = atm.dailyAvgWithdrawal();
            long planned = req.amount() == null ? predicted : req.amount();
            if (planned <= 0) {
                throw new BusinessException(409, "加钞额必须为正数");
            }
            if (planned > atm.capacity()) {
                throw new BusinessException(409,
                        "加钞额 " + planned + " 超过钞箱容量 " + atm.capacity() + "（恰等容量合法）");
            }
            long seq = repo.nextPlanNoSequence();
            String planNo = "P" + DateTimeFormatter.BASIC_ISO_DATE.format(businessDate)
                    + "-" + String.format("%05d", seq);

            PlanRow row = new PlanRow();
            row.setPlanNo(planNo);
            row.setAtmId(atm.id());
            row.setBusinessDate(businessDate);
            row.setPlanType(PlanType.REGULAR);
            row.setQueuePosition(repo.maxQueuePosition(atm.id()) + 1);
            row.setPredictedAmount(predicted);
            row.setPlannedAmount(planned);
            row.setStatus(PlanStatus.DRAFT);
            row.setCreatedAt(now);
            try {
                repo.insertPlan(row);
            } catch (DuplicateKeyException e) {
                throw new BusinessException(409, "该 ATM 当日（UTC）已存在加钞计划，唯一受理");
            }
            return Outcome.ok(toResponse(row, atm.code(), false, "计划已编制，待审批"));
        });
    }

    // ==================== 审批 ====================

    @Transactional
    public Outcome approve(long planId, ApproveRequest req) {
        String fingerprint = fingerprint("APPROVE", req.requestId(), String.valueOf(planId));
        return withIdempotency(req.requestId(), "APPROVE", fingerprint, planId, () -> {
            PlanRow plan = lockPlan(planId);
            if (plan.getSealedAt() != null) {
                return lateOutcome(plan, "APPROVE", "计划已封存，审批迟到仅登记");
            }
            if (plan.getStatus() != PlanStatus.DRAFT) {
                throw new BusinessException(409, "仅 DRAFT 状态可审批，当前状态: " + plan.getStatus());
            }
            List<PlanRow> executing = repo.findExecutingByAtm(plan.getAtmId());
            if (!executing.isEmpty()) {
                throw new BusinessException(409, "同一 ATM 同时刻只允许一张执行中加钞单，当前执行中计划: "
                        + executing.get(0).getPlanNo());
            }
            Instant now = Instant.now(clock);
            plan.setApprovedAt(now);
            plan.setEscortDeadline(now.atZone(ZoneOffset.UTC).plusHours(ESCORT_LIMIT_HOURS).toInstant());
            plan.setStatus(PlanStatus.APPROVED);
            repo.updatePlan(plan);
            return Outcome.ok(toResponse(plan, atmCode(plan), false,
                    "审批通过，押运时限至 " + plan.getEscortDeadline()));
        });
    }

    // ==================== 节假日临时计划（快速通道） ====================

    /**
     * 按合成节假日日历预测节前 3 日取现峰值，对预测超日均 150% 阈值的网点自动生成临时计划。
     * 临时计划走快速通道：免常规逐级审批（生成即 APPROVED），押运时限压缩为 2 小时；
     * 与在途执行中计划冲突时按金额大者优先，裁决与状态变更在本事务内一并提交，
     * 被让路计划转 YIELDED 并保留排队位置。快速通道结论逐条留痕。
     */
    @Transactional
    public Outcome generateTemporaryPlans(ForecastGenerateRequest req) {
        String fingerprint = fingerprint("FORECAST_TEMP", req.requestId(), req.holidayDate().toString());
        return withIdempotency(req.requestId(), "FORECAST_TEMP", fingerprint, null, ForecastResponse.class, () -> {
            HolidayRow holiday = repo.findHolidayByDate(req.holidayDate())
                    .orElseThrow(() -> new BusinessException(404, "节假日不存在于合成日历: " + req.holidayDate()));
            Instant now = Instant.now(clock);
            LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
            LocalDate windowStart = holiday.holidayDate().minusDays(3);
            LocalDate windowEnd = holiday.holidayDate().minusDays(1);
            if (today.isBefore(windowStart) || today.isAfter(windowEnd)) {
                throw new BusinessException(409, "当前日期 " + today + " 不在节前 3 日窗口 "
                        + windowStart + " ~ " + windowEnd + "（UTC）内");
            }
            List<ForecastResponse.Item> items = new ArrayList<>();
            for (Atm atm : repo.findAllAtms()) {
                items.add(generateForAtm(atm, holiday, today, now));
            }
            return Outcome.ok(new ForecastResponse(holiday.holidayDate(), holiday.name(),
                    holiday.peakFactorBp(), windowStart, windowEnd, items));
        });
    }

    private ForecastResponse.Item generateForAtm(Atm atm, HolidayRow holiday, LocalDate businessDate, Instant now) {
        long predicted = atm.dailyAvgWithdrawal() * holiday.peakFactorBp() / 100;
        long threshold = atm.dailyAvgWithdrawal() * PEAK_THRESHOLD_BP / 100;
        if (predicted <= threshold) {
            return new ForecastResponse.Item(atm.code(), predicted, threshold, false, null, null,
                    "预测峰值未超日均 150% 阈值，不生成临时计划");
        }
        if (atm.frozen()) {
            return new ForecastResponse.Item(atm.code(), predicted, threshold, false, null, null,
                    "该 ATM 已因押运异常冻结，不生成临时计划");
        }
        if (repo.findPlanByAtmDateAndType(atm.id(), businessDate, PlanType.TEMPORARY).isPresent()) {
            return new ForecastResponse.Item(atm.code(), predicted, threshold, false, null, null,
                    "该 ATM 当日（UTC）已存在临时计划，不重复生成");
        }
        long planned = Math.min(predicted, atm.capacity());

        PlanRow temp = new PlanRow();
        temp.setPlanNo("T" + DateTimeFormatter.BASIC_ISO_DATE.format(businessDate)
                + "-" + String.format("%05d", repo.nextPlanNoSequence()));
        temp.setAtmId(atm.id());
        temp.setBusinessDate(businessDate);
        temp.setPlanType(PlanType.TEMPORARY);
        temp.setQueuePosition(repo.maxQueuePosition(atm.id()) + 1);
        temp.setPredictedAmount(predicted);
        temp.setPlannedAmount(planned);
        temp.setCreatedAt(now);

        // 冲突裁决：与执行中计划按金额大者优先（恰等金额在途优先），裁决与状态变更同事务
        List<PlanRow> executing = repo.findExecutingByAtm(atm.id());
        if (executing.isEmpty()) {
            fastApprove(temp, now);
            insertPlanGuardingDuplicate(temp);
            if (temp.getId() == null) {
                return new ForecastResponse.Item(atm.code(), predicted, threshold, false, null, null,
                        "该 ATM 当日（UTC）已存在临时计划，不重复生成");
            }
            repo.insertFastTrackAudit(temp.getId(), "AUTO_APPROVED",
                    "快速通道：节前峰值预测 " + predicted + " 超日均 150% 阈值 " + threshold
                            + "，免逐级审批直接放行，押运时限 2 小时至 " + temp.getEscortDeadline(), now);
            return new ForecastResponse.Item(atm.code(), predicted, threshold, true,
                    temp.getId(), temp.getStatus().name(), "已生成临时计划并快速放行");
        }

        PlanRow inflight = lockPlan(executing.get(0).getId()); // 行锁串行化裁决
        if (planned > inflight.getPlannedAmount()) {
            // 临时计划金额大者优先：在途计划让路但保留排队位置
            inflight.setStatus(PlanStatus.YIELDED);
            repo.updatePlan(inflight);
            repo.insertFastTrackAudit(inflight.getId(), "CONFLICT_YIELD",
                    "与临时计划冲突：金额 " + inflight.getPlannedAmount() + " < " + planned
                            + "，让路但保留排队位置 " + inflight.getQueuePosition(), now);
            fastApprove(temp, now);
            insertPlanGuardingDuplicate(temp);
            if (temp.getId() == null) {
                // 并发下当日临时计划已存在：回滚由唯一约束保证不发生，此处兜底
                throw new BusinessException(409, "该 ATM 当日（UTC）已存在临时计划，冲突裁决未提交");
            }
            repo.insertFastTrackAudit(temp.getId(), "CONFLICT_WIN",
                    "与在途计划 " + inflight.getPlanNo() + " 冲突：金额 " + planned + " 优先，"
                            + "快速放行，押运时限 2 小时至 " + temp.getEscortDeadline(), now);
            return new ForecastResponse.Item(atm.code(), predicted, threshold, true,
                    temp.getId(), temp.getStatus().name(),
                    "冲突裁决：临时计划金额优先，在途计划 " + inflight.getPlanNo() + " 让路");
        }
        // 在途计划金额大或恰等：临时计划让路，保留排队位置
        temp.setStatus(PlanStatus.YIELDED);
        insertPlanGuardingDuplicate(temp);
        if (temp.getId() == null) {
            return new ForecastResponse.Item(atm.code(), predicted, threshold, false, null, null,
                    "该 ATM 当日（UTC）已存在临时计划，不重复生成");
        }
        repo.insertFastTrackAudit(temp.getId(), "CONFLICT_YIELD",
                "与在途计划 " + inflight.getPlanNo() + " 冲突：金额 " + planned + " 不优先于 "
                        + inflight.getPlannedAmount() + "，临时计划让路，保留排队位置 " + temp.getQueuePosition(), now);
        return new ForecastResponse.Item(atm.code(), predicted, threshold, true,
                temp.getId(), temp.getStatus().name(),
                "冲突裁决：在途计划金额优先，临时计划让路排队");
    }

    /** 快速通道放行：免逐级审批，直接 APPROVED 并起算 2 小时押运时限。 */
    private void fastApprove(PlanRow temp, Instant now) {
        temp.setStatus(PlanStatus.APPROVED);
        temp.setApprovedAt(now);
        temp.setEscortDeadline(now.atZone(ZoneOffset.UTC).plusHours(TEMP_ESCORT_LIMIT_HOURS).toInstant());
    }

    /** 插入临时计划；当日同类唯一约束冲突时置空 ID 由调用方按“不重复生成”处理。 */
    private void insertPlanGuardingDuplicate(PlanRow temp) {
        try {
            repo.insertPlan(temp);
        } catch (DuplicateKeyException e) {
            temp.setId(null);
        }
    }

    /**
     * 让路计划恢复执行：仅 YIELDED 可恢复；须同一 ATM 无执行中计划，
     * 且按排队位置轮到该计划（队列内位置最小者优先）。恢复后重新起算押运时限
     * （常规 4 小时 / 临时 2 小时）。
     */
    @Transactional
    public Outcome resume(long planId, ResumeRequest req) {
        String fingerprint = fingerprint("RESUME", req.requestId(), String.valueOf(planId));
        return withIdempotency(req.requestId(), "RESUME", fingerprint, planId, () -> {
            PlanRow plan = lockPlan(planId);
            if (plan.getSealedAt() != null) {
                return lateOutcome(plan, "RESUME", "计划已封存，恢复请求迟到仅登记");
            }
            if (plan.getStatus() != PlanStatus.YIELDED) {
                throw new BusinessException(409, "仅 YIELDED 让路状态可恢复执行，当前状态: " + plan.getStatus());
            }
            Atm atm = repo.findAtmById(plan.getAtmId())
                    .orElseThrow(() -> new BusinessException(404, "ATM 不存在: " + plan.getAtmId()));
            if (atm.frozen()) {
                throw new BusinessException(409, "该 ATM 已因押运异常冻结，让路计划不可恢复: " + atm.code());
            }
            List<PlanRow> executing = repo.findExecutingByAtm(plan.getAtmId());
            if (!executing.isEmpty()) {
                throw new BusinessException(409, "同一 ATM 同时刻只允许一张执行中加钞单，当前执行中计划: "
                        + executing.get(0).getPlanNo());
            }
            List<PlanRow> yielded = repo.findYieldedByAtm(plan.getAtmId());
            if (!yielded.isEmpty() && !yielded.get(0).getId().equals(plan.getId())) {
                throw new BusinessException(409, "排队位置 " + yielded.get(0).getQueuePosition()
                        + " 的计划 " + yielded.get(0).getPlanNo() + " 未恢复，按队列顺序受理");
            }
            Instant now = Instant.now(clock);
            long limitHours = plan.getPlanType() == PlanType.TEMPORARY
                    ? TEMP_ESCORT_LIMIT_HOURS : ESCORT_LIMIT_HOURS;
            plan.setStatus(PlanStatus.APPROVED);
            plan.setApprovedAt(now);
            plan.setEscortDeadline(now.atZone(ZoneOffset.UTC).plusHours(limitHours).toInstant());
            repo.updatePlan(plan);
            if (plan.getPlanType() == PlanType.TEMPORARY) {
                repo.insertFastTrackAudit(plan.getId(), "RESUMED",
                        "让路临时计划恢复执行，押运时限 2 小时至 " + plan.getEscortDeadline(), now);
            }
            return Outcome.ok(toResponse(plan, atm.code(), false,
                    "让路计划已恢复执行，押运时限至 " + plan.getEscortDeadline()));
        });
    }

    // ==================== 押运打卡 ====================

    @Transactional
    public Outcome checkin(long planId, CheckinRequest req) {
        String fingerprint = fingerprint("CHECKIN", req.requestId(), String.valueOf(planId), req.custodianCode());
        return withIdempotency(req.requestId(), "CHECKIN", fingerprint, planId, () -> {
            PlanRow plan = lockPlan(planId);
            if (plan.getSealedAt() != null) {
                return lateOutcome(plan, "CHECKIN", "计划已封存，押运打卡迟到仅登记");
            }
            Custodian custodian = repo.findCustodianByCode(req.custodianCode())
                    .orElseThrow(() -> new BusinessException(404, "押运员不存在: " + req.custodianCode()));
            if (custodian.frozen()) {
                throw new BusinessException(409, "押运员已被冻结，不得承接押运任务: " + custodian.code());
            }
            if (plan.getStatus() == PlanStatus.ESCORT_ABNORMAL) {
                throw new BusinessException(409, "该单押运已异常，打卡不再受理");
            }
            if (plan.getStatus() != PlanStatus.APPROVED) {
                throw new BusinessException(409, "仅审批通过后可押运打卡，当前状态: " + plan.getStatus());
            }
            Instant now = Instant.now(clock);
            // 恰满 4 小时未完成双人打卡：自动转押运异常并冻结 ATM 后续计划（副作用随本次受理提交）
            if (!now.isBefore(plan.getEscortDeadline())) {
                markEscortAbnormal(plan);
                return new Outcome(409, toResponse(plan, atmCode(plan), false,
                        "押运时限恰满/已过，自动转押运异常并冻结该 ATM 后续计划"));
            }
            if (repo.existsCheckin(plan.getId(), custodian.id())) {
                throw new BusinessException(409, "同一押运员不得重复打卡: " + custodian.code());
            }
            int order = repo.countCheckins(plan.getId()) + 1; // 行锁串行化，按时间定序
            repo.insertCheckin(new CheckinRow(null, plan.getId(), custodian.id(), order, now, now));
            String message;
            if (order == 1) {
                message = "第一名押运员已打卡，单人打卡不生效，等待第二名不同押运员";
            } else {
                plan.setStatus(PlanStatus.ESCORTED);
                plan.setEscortCompletedAt(now);
                repo.updatePlan(plan);
                message = "第二名押运员已打卡，双人押运完成";
            }
            return Outcome.ok(toResponse(plan, atmCode(plan), false, message));
        });
    }

    private void markEscortAbnormal(PlanRow plan) {
        plan.setStatus(PlanStatus.ESCORT_ABNORMAL);
        repo.updatePlan(plan);
        repo.freezeAtm(plan.getAtmId()); // 冻结后续计划
    }

    // ==================== 双人清点 ====================

    @Transactional
    public Outcome count(long planId, CountRequest req) {
        String fingerprint = fingerprint("COUNT", req.requestId(), String.valueOf(planId),
                String.valueOf(req.countedAmount()), req.custodianCode());
        return withIdempotency(req.requestId(), "COUNT", fingerprint, planId, () -> {
            PlanRow plan = lockPlan(planId);
            if (plan.getSealedAt() != null) {
                return lateOutcome(plan, "COUNT", "计划已封存，清点数据迟到仅登记");
            }
            Custodian custodian = repo.findCustodianByCode(req.custodianCode())
                    .orElseThrow(() -> new BusinessException(404, "押运员不存在: " + req.custodianCode()));
            if (plan.getStatus() != PlanStatus.ESCORTED) {
                throw new BusinessException(409, "仅双人押运完成后可清点，当前状态: " + plan.getStatus());
            }
            Instant now = Instant.now(clock);
            plan.setCountedAmount(req.countedAmount());
            plan.setCountedAt(now);
            plan.setCountCustodianId(custodian.id());
            plan.setStatus(PlanStatus.COUNTED);
            repo.updatePlan(plan);
            return Outcome.ok(toResponse(plan, atmCode(plan), false, "双人清点完成，待设备确认"));
        });
    }

    // ==================== 设备确认与三方核销（同事务挂账） ====================

    @Transactional
    public Outcome deviceConfirm(long planId, DeviceConfirmRequest req) {
        String fingerprint = fingerprint("DEVICE_CONFIRM", req.requestId(), String.valueOf(planId),
                String.valueOf(req.deviceAmount()));
        return withIdempotency(req.requestId(), "DEVICE_CONFIRM", fingerprint, planId, () -> {
            PlanRow plan = lockPlan(planId);
            if (plan.getSealedAt() != null) {
                return lateOutcome(plan, "DEVICE_CONFIRM", "计划已封存，设备确认迟到仅登记");
            }
            if (plan.getStatus() != PlanStatus.COUNTED) {
                throw new BusinessException(409, "仅清点完成后可设备确认，当前状态: " + plan.getStatus());
            }
            Instant now = Instant.now(clock);
            plan.setDeviceAmount(req.deviceAmount());
            plan.setDeviceConfirmedAt(now);

            long planned = plan.getPlannedAmount();
            long counted = plan.getCountedAmount();
            long device = req.deviceAmount();
            String message;
            if (planned == counted && counted == device) {
                // 三方一致方可核销
                plan.setStatus(PlanStatus.RECONCILED);
                plan.setReconciledAt(now);
                message = "计划数、清点数、设备入账数三方一致，核销完成";
            } else {
                // 任两方不一致转长短款；挂账与核销同事务，不得核销完成而挂账未记
                long discrepancyAmount = counted - planned; // 挂账金额=实际-计划，正长款负短款
                LocalDate occurred = LocalDate.ofInstant(now, ZoneOffset.UTC);
                DiscrepancyRow d = new DiscrepancyRow();
                d.setPlanId(plan.getId());
                d.setAmount(discrepancyAmount);
                d.setStatus(DiscrepancyStatus.OPEN);
                d.setResponsibleCustodianId(plan.getCountCustodianId());
                d.setOccurredDate(occurred);
                d.setDueDate(occurred.plusDays(DISCREPANCY_DUE_DAYS));
                d.setCreatedAt(now);
                repo.insertDiscrepancy(d);
                plan.setDiscrepancyId(d.getId());
                plan.setStatus(PlanStatus.DISCREPANCY);
                message = "三方不一致转长短款，挂账金额（实际-计划）=" + discrepancyAmount
                        + "，须于 " + d.getDueDate() + "（UTC，含当日）前销账";
            }
            repo.updatePlan(plan);
            return Outcome.ok(toResponse(plan, atmCode(plan), false, message));
        });
    }

    // ==================== 长短款销账 ====================

    @Transactional
    public Outcome settleDiscrepancy(long planId, SettleDiscrepancyRequest req) {
        String fingerprint = fingerprint("SETTLE_DISCREPANCY", req.requestId(), String.valueOf(planId));
        return withIdempotency(req.requestId(), "SETTLE_DISCREPANCY", fingerprint, planId, () -> {
            PlanRow plan = lockPlan(planId);
            if (plan.getSealedAt() != null) {
                return lateOutcome(plan, "SETTLE_DISCREPANCY", "计划已封存，销账迟到仅登记");
            }
            DiscrepancyRow d = repo.findDiscrepancyByPlanForUpdate(planId)
                    .orElseThrow(() -> new BusinessException(404, "该计划无长短款挂账"));
            if (d.getStatus() != DiscrepancyStatus.OPEN) {
                throw new BusinessException(409, "挂账当前状态不可销账: " + d.getStatus());
            }
            LocalDate today = LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC);
            if (today.isAfter(d.getDueDate())) {
                // 逾期升级并冻结责任人新任务（副作用随本次受理提交），向客户端返回 409
                d.setStatus(DiscrepancyStatus.ESCALATED);
                repo.updateDiscrepancy(d);
                repo.freezeCustodian(d.getResponsibleCustodianId());
                return new Outcome(409, toResponse(plan, atmCode(plan), false,
                        "销账逾期（截止 " + d.getDueDate() + " UTC），已升级并冻结责任人新任务"));
            }
            // 恰第 3 日（today==dueDate）提交仍受理
            Instant now = Instant.now(clock);
            d.setStatus(DiscrepancyStatus.SETTLED);
            d.setSettledAt(now);
            repo.updateDiscrepancy(d);
            plan.setStatus(PlanStatus.RECONCILED);
            plan.setReconciledAt(now);
            repo.updatePlan(plan);
            return Outcome.ok(toResponse(plan, atmCode(plan), false, "长短款已按期销账，核销闭环完成"));
        });
    }

    // ==================== 日结封存（终态） ====================

    @Transactional
    public Outcome seal(long planId, SealRequest req) {
        String fingerprint = fingerprint("SEAL", req.requestId(), String.valueOf(planId));
        return withIdempotency(req.requestId(), "SEAL", fingerprint, planId, () -> {
            PlanRow plan = lockPlan(planId);
            if (plan.getSealedAt() != null) {
                // 封存为终态：重复封存返回幂等成功
                return Outcome.ok(toResponse(plan, atmCode(plan), false, "计划已封存，重复封存幂等成功"));
            }
            Instant now = Instant.now(clock);
            plan.setSealedAt(now);
            plan.setStatus(PlanStatus.SEALED);
            repo.updatePlan(plan);
            return Outcome.ok(toResponse(plan, atmCode(plan), false, "已日结封存，终态"));
        });
    }

    // ==================== 查询与复算 ====================

    @Transactional(readOnly = true)
    public PlanResponse getPlan(long planId) {
        PlanRow plan = repo.findPlanById(planId)
                .orElseThrow(() -> new BusinessException(404, "计划不存在: " + planId));
        return toResponse(plan, atmCode(plan), false, null);
    }

    @Transactional(readOnly = true)
    public List<PlanResponse> ledger() {
        return repo.findAllPlans().stream()
                .map(p -> toResponse(p, atmCode(p), false, null))
                .toList();
    }

    @Transactional(readOnly = true)
    public ReconciliationResponse reconciliation(long planId) {
        PlanRow plan = repo.findPlanById(planId)
                .orElseThrow(() -> new BusinessException(404, "计划不存在: " + planId));
        boolean ready = plan.getCountedAmount() != null && plan.getDeviceAmount() != null;
        boolean consistent = ready
                && plan.getPlannedAmount() == plan.getCountedAmount().longValue()
                && plan.getCountedAmount().longValue() == plan.getDeviceAmount().longValue();
        Long discrepancyAmount = ready ? plan.getCountedAmount() - plan.getPlannedAmount() : null;
        return new ReconciliationResponse(
                plan.getId(),
                plan.getStatus().name(),
                plan.getPlannedAmount(),
                plan.getCountedAmount(),
                plan.getDeviceAmount(),
                consistent,
                discrepancyAmount,
                plan.getDiscrepancyId());
    }

    @Transactional(readOnly = true)
    public DiscrepancyResponse getDiscrepancy(long planId) {
        DiscrepancyRow d = repo.findDiscrepancyByPlan(planId)
                .orElseThrow(() -> new BusinessException(404, "该计划无挂账记录"));
        String code = repo.findCustodianById(d.getResponsibleCustodianId())
                .map(Custodian::code).orElse(null);
        return DiscrepancyResponse.of(d, code);
    }

    /** 计划的快速通道结论留痕。 */
    @Transactional(readOnly = true)
    public List<FastTrackAuditResponse> fastTrackAudits(long planId) {
        return repo.findFastTrackAuditsByPlan(planId).stream()
                .map(FastTrackAuditResponse::of)
                .toList();
    }

    /** 日结核对：指定 UTC 营业日的全部计划与当日快速通道结论留痕。 */
    @Transactional(readOnly = true)
    public DailyReconciliationResponse dailyReconciliation(LocalDate date) {
        List<PlanResponse> plans = repo.findPlansByBusinessDate(date).stream()
                .map(p -> toResponse(p, atmCode(p), false, null))
                .toList();
        List<FastTrackAuditResponse> audits = repo.findFastTrackAuditsByCreatedDate(date).stream()
                .map(FastTrackAuditResponse::of)
                .toList();
        return new DailyReconciliationResponse(date, plans, audits);
    }

    // ==================== 超时/逾期扫描 ====================

    /**
     * 扫描并处理两类时限：APPROVED 单押运时限恰满/已过转异常冻结 ATM；
     * OPEN 挂账超过 dueDate 升级并冻结责任人。可由定时任务与测试可控时钟直接调用。
     */
    @Transactional
    public int sweepTimeouts() {
        return sweepTimeouts(Instant.now(clock));
    }

    @Transactional
    public int sweepTimeouts(Instant now) {
        int affected = 0;
        for (PlanRow plan : repo.findAllPlans()) {
            if (plan.getStatus() == PlanStatus.APPROVED
                    && plan.getEscortDeadline() != null
                    && !now.isBefore(plan.getEscortDeadline())) {
                PlanRow locked = lockPlan(plan.getId());
                if (locked.getStatus() == PlanStatus.APPROVED) {
                    markEscortAbnormal(locked);
                    affected++;
                }
            }
            if (plan.getDiscrepancyId() != null) {
                Optional<DiscrepancyRow> opt = repo.findDiscrepancyByPlanForUpdate(plan.getId());
                if (opt.isPresent()) {
                    DiscrepancyRow d = opt.get();
                    LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
                    if (d.getStatus() == DiscrepancyStatus.OPEN && today.isAfter(d.getDueDate())) {
                        d.setStatus(DiscrepancyStatus.ESCALATED);
                        repo.updateDiscrepancy(d);
                        repo.freezeCustodian(d.getResponsibleCustodianId());
                        affected++;
                    }
                }
            }
        }
        return affected;
    }

    // ==================== 幂等支撑 ====================

    /**
     * 以 requestId 为键执行动作。先插入占位留痕裁决并发，再执行业务，最后同事务回写首次响应；
     * 业务异常会随外层事务回滚并清除占位。同键同指纹返回首次结果，同键异指纹或处理中冲突返回 409。
     */
    private Outcome withIdempotency(String requestId, String action, String fingerprint,
                                    Long planId, java.util.function.Supplier<Outcome> task) {
        return withIdempotency(requestId, action, fingerprint, planId, PlanResponse.class, task);
    }

    private Outcome withIdempotency(String requestId, String action, String fingerprint, Long planId,
                                    Class<?> responseType, java.util.function.Supplier<Outcome> task) {
        Optional<IdempotentRow> existing = repo.findIdempotent(requestId);
        if (existing.isPresent()) {
            return resolveExisting(requestId, fingerprint, existing.get(), responseType);
        }
        try {
            repo.insertIdempotent(requestId, action, fingerprint, planId, null, null, Instant.now(clock));
        } catch (DuplicateKeyException e) {
            // 并发同键：以已存在留痕为准
            return resolveExisting(requestId, fingerprint,
                    repo.findIdempotent(requestId)
                            .orElseThrow(() -> new BusinessException(409, "幂等键并发冲突，请重试")),
                    responseType);
        }
        Outcome outcome = task.get();
        String bodyJson;
        try {
            bodyJson = objectMapper.writeValueAsString(outcome.body());
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应序列化失败", e);
        }
        repo.completeIdempotent(requestId, outcome.statusCode(), bodyJson);
        return outcome;
    }

    private Outcome resolveExisting(String requestId, String fingerprint, IdempotentRow row,
                                    Class<?> responseType) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw new BusinessException(409, "requestId=" + requestId + " 已用于不同业务参数，拒绝处理");
        }
        if (row.statusCode() == null || row.responseBody() == null) {
            throw new BusinessException(409, "requestId=" + requestId + " 的首次请求仍在处理中，请勿并发重放");
        }
        try {
            return new Outcome(row.statusCode(),
                    objectMapper.readValue(row.responseBody(), responseType));
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    // ==================== 私有工具 ====================

    private PlanRow lockPlan(long planId) {
        return repo.findPlanByIdForUpdate(planId)
                .orElseThrow(() -> new BusinessException(404, "计划不存在: " + planId));
    }

    private String atmCode(PlanRow plan) {
        return repo.findAtmById(plan.getAtmId()).map(Atm::code).orElse(null);
    }

    private Outcome lateOutcome(PlanRow plan, String eventType, String message) {
        Instant now = Instant.now(clock);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(toResponse(plan, atmCode(plan), true, message));
        } catch (Exception e) {
            payload = message;
        }
        repo.insertLateEvent(plan.getId(), eventType, payload, now);
        return Outcome.ok(toResponse(plan, atmCode(plan), true, message));
    }

    private PlanResponse toResponse(PlanRow p, String atmCode, boolean late, String message) {
        return PlanResponse.of(p, atmCode, p.getId() == null ? 0 : repo.countCheckins(p.getId()),
                late, message);
    }

    private static String fingerprint(String action, String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder(action);
            for (String part : parts) {
                sb.append('|').append(part);
            }
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
