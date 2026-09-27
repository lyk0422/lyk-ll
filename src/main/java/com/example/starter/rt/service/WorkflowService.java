package com.example.starter.rt.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.rt.domain.OperationType;
import com.example.starter.rt.domain.PlanStatus;
import com.example.starter.rt.dto.ApprovalResponse;
import com.example.starter.rt.dto.AuditResponse;
import com.example.starter.rt.dto.ClosureResponse;
import com.example.starter.rt.dto.ConstraintCheckItem;
import com.example.starter.rt.dto.FractionResponse;
import com.example.starter.rt.dto.LateFractionResponse;
import com.example.starter.rt.dto.RecordedResponse;
import com.example.starter.rt.dto.RejectionBody;
import com.example.starter.rt.dto.VerificationResponse;
import com.example.starter.rt.repository.DirectoryRepository;
import com.example.starter.rt.repository.DirectoryRepository.ConstraintItemRow;
import com.example.starter.rt.repository.PlanRepository;
import com.example.starter.rt.repository.PlanRepository.ActivePlanRow;
import com.example.starter.rt.repository.PlanRepository.OarDoseRow;
import com.example.starter.rt.repository.PlanRepository.PlanVersionRow;
import com.example.starter.rt.repository.WorkflowRepository;
import com.example.starter.rt.repository.WorkflowRepository.AuditItemRow;
import com.example.starter.rt.repository.WorkflowRepository.AuditRow;
import com.example.starter.rt.repository.WorkflowRepository.FractionRow;
import com.example.starter.rt.repository.WorkflowRepository.VerificationRow;
import com.example.starter.rt.web.ApiException;

/**
 * 放疗计划核心工作流服务：物理审核→医师批准→首次模体验证→分次执行→疗程结案。
 *
 * <p>并发控制：涉及患者唯一在执行计划的操作（批准/分次/结案）先对患者主记录加行锁
 * （SELECT ... FOR UPDATE），再锁计划版本，锁顺序全局一致（患者→版本）；
 * 审核/验证锁计划版本。行锁把同实体并发请求串行化，先提交者写入首次结果，后到者走幂等重放。</p>
 *
 * <p>所有业务结论（成功 200、业务拒绝 422/409）均以 {@link RecordedResponse} 返回，
 * 与业务写库、requestId 幂等记录在同一事务提交；仅资源不存在（404）在进入幂等动作前抛出。</p>
 */
@Service
public class WorkflowService {

    private final PlanRepository planRepository;
    private final DirectoryRepository directoryRepository;
    private final WorkflowRepository workflowRepository;
    private final DoseEvaluation doseEvaluation;
    private final IdempotencyTemplate idempotency;
    private final ScheduleService scheduleService;
    private final Clock clock;

    public WorkflowService(PlanRepository planRepository, DirectoryRepository directoryRepository,
                           WorkflowRepository workflowRepository, DoseEvaluation doseEvaluation,
                           IdempotencyTemplate idempotency, ScheduleService scheduleService,
                           Clock clock) {
        this.planRepository = planRepository;
        this.directoryRepository = directoryRepository;
        this.workflowRepository = workflowRepository;
        this.doseEvaluation = doseEvaluation;
        this.idempotency = idempotency;
        this.scheduleService = scheduleService;
        this.clock = clock;
    }

    // ============================ 物理审核 ============================

    /** 物理审核：总剂量勾稽 + 约束模板逐项核对（恰等限值合法），任一项超限直接拒绝。 */
    @Transactional
    public RecordedResponse audit(String patientId, String planCode, int versionNo, String requestId) {
        PlanVersionRow version = lockVersion(patientId, planCode, versionNo);
        return idempotency.execute(OperationType.AUDIT, requestId,
                baseParams(patientId, planCode, versionNo), () -> doAudit(version, requestId));
    }

    private RecordedResponse doAudit(PlanVersionRow version, String requestId) {
        if (!PlanStatus.DESIGN.name().equals(version.status())) {
            return reject(422, "PLAN_NOT_IN_DESIGN",
                    "计划版本当前状态 " + version.status() + "，仅 DESIGN 状态可提交物理审核");
        }
        LocalDateTime now = LocalDateTime.now(clock);

        boolean totalDoseOk = doseEvaluation.totalDoseMatches(
                version.prescribedTotalDoseCgy(), version.fractionDoseCgy(), version.fractionCount());
        List<ConstraintItemRow> templateItems =
                directoryRepository.findConstraintItems(version.templateCode());
        List<OarDoseRow> plannedDoses = planRepository.findOarDoses(version.id());
        DoseEvaluation.CheckResult check = doseEvaluation.evaluate(templateItems, plannedDoses);

        if (!totalDoseOk || !check.exactCoverageAndWithin()) {
            String reason = buildAuditFailureReason(totalDoseOk, check);
            return new RecordedResponse(422,
                    RejectionBody.withDetails("AUDIT_REJECTED", reason, List.of(check.items().toArray())));
        }

        long auditId = workflowRepository.insertAudit(version.id(), requestId, true, true, now);
        for (ConstraintCheckItem item : check.items()) {
            workflowRepository.insertAuditItem(auditId, item.organCode(), item.organName(),
                    item.metricType(), item.metricRef(), item.plannedValue(), item.limitValue(),
                    item.withinLimit());
        }
        planRepository.markReviewed(version.id(), now);
        AuditResponse response = new AuditResponse(version.id(), version.versionNo(),
                PlanStatus.REVIEWED.name(), requestId, false, true, true, check.items());
        return new RecordedResponse(200, response);
    }

    private String buildAuditFailureReason(boolean totalDoseOk, DoseEvaluation.CheckResult check) {
        if (!totalDoseOk) {
            return "总剂量勾稽不一致：分次剂量×次数≠处方总剂量，审核拒绝";
        }
        if (!check.missingMetrics().isEmpty()) {
            return "申报剂量未覆盖约束模板全部指标项，缺失：" + String.join(",", check.missingMetrics());
        }
        if (!check.extraMetrics().isEmpty()) {
            return "申报剂量存在模板之外的指标项：" + String.join(",", check.extraMetrics());
        }
        return "存在超过限值的危及器官剂量项（恰等限值合法），审核拒绝";
    }

    // ============================ 医师批准 ============================

    /**
     * 医师批准。同一患者同时刻仅一个执行中计划；同计划换版批准时旧版本在本事务内即刻失效。
     * 并发批准由患者行锁串行化，唯一成功，其余返回 409。
     */
    @Transactional
    public RecordedResponse approve(String patientId, String planCode, int versionNo, String requestId) {
        ensurePatientLock(patientId);
        PlanVersionRow version = lockVersion(patientId, planCode, versionNo);
        return idempotency.execute(OperationType.APPROVAL, requestId,
                baseParams(patientId, planCode, versionNo),
                () -> doApprove(patientId, planCode, version, requestId));
    }

    private RecordedResponse doApprove(String patientId, String planCode, PlanVersionRow version,
                                       String requestId) {
        if (!PlanStatus.REVIEWED.name().equals(version.status())) {
            return reject(422, "PLAN_NOT_REVIEWED",
                    "计划版本当前状态 " + version.status() + "，须物理审核通过(REVIEWED)后方可批准");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        ActivePlanRow active = planRepository.findActivePlan(patientId).orElse(null);

        if (active == null) {
            try {
                planRepository.insertActivePlan(patientId, planCode, version.id(), now);
            } catch (DuplicateKeyException e) {
                return reject(409, "ANOTHER_PLAN_ACTIVE",
                        "患者已有执行中计划，同一患者同时刻只允许一个执行中计划");
            }
        } else if ("CLOSED".equals(active.status())) {
            return reject(409, "PATIENT_COURSE_CLOSED",
                    "患者疗程已结案（终态），不能再批准执行计划");
        } else if (!active.planCode().equals(planCode)) {
            return reject(409, "ANOTHER_PLAN_ACTIVE",
                    "患者已有执行中计划 " + active.planCode() + "，同一患者同时刻只允许一个执行中计划");
        } else if (active.planVersionId() == version.id()) {
            return reject(409, "PLAN_ALREADY_APPROVED", "该计划版本已批准，请勿重复批准");
        } else {
            // 同计划换版：旧版本即刻失效，守卫指向新版本，全部在同一事务内完成，杜绝新旧版本同时可执行。
            int invalidated = planRepository.invalidateOtherVersions(
                    patientId, planCode, version.id(), now);
            int repointed = planRepository.pointActivePlanTo(patientId, planCode, version.id(), now);
            if (invalidated == 0 || repointed == 0) {
                return reject(409, "PLAN_REVISION_RACE",
                        "换版批准并发冲突：旧版本失效或守卫切换未成功");
            }
            // 改版联动：同事务取消旧版本全部未开始排程并释放资源；已执行分次剂量与实绩不改写。
            scheduleService.cancelActiveSchedulesOfOtherVersions(patientId, planCode, version.id());
        }

        planRepository.markApproved(version.id(), now);
        ApprovalResponse response = new ApprovalResponse(version.id(), version.versionNo(),
                PlanStatus.APPROVED.name(), requestId, false);
        return new RecordedResponse(200, response);
    }

    // ============================ 首次治疗前模体验证 ============================

    /** 模体验证：偏差 |实测-计划|/计划×100%，恰 3% 合法，超过 3% 不得执行（失败也留痕形成验证链）。 */
    @Transactional
    public RecordedResponse verify(String patientId, String planCode, int versionNo,
                                   long plannedDoseCgy, long measuredDoseCgy, String requestId) {
        PlanVersionRow version = lockVersion(patientId, planCode, versionNo);
        Map<String, Object> params = new LinkedHashMap<>(baseParams(patientId, planCode, versionNo));
        params.put("plannedDoseCgy", plannedDoseCgy);
        params.put("measuredDoseCgy", measuredDoseCgy);
        return idempotency.execute(OperationType.VERIFICATION, requestId, params,
                () -> doVerify(version, plannedDoseCgy, measuredDoseCgy, requestId));
    }

    private RecordedResponse doVerify(PlanVersionRow version, long plannedDoseCgy,
                                      long measuredDoseCgy, String requestId) {
        if (PlanStatus.INVALID.name().equals(version.status())) {
            return reject(422, "PLAN_VERSION_INVALID", "该计划版本已失效，不能进行模体验证");
        }
        if (PlanStatus.DESIGN.name().equals(version.status())
                || PlanStatus.REVIEWED.name().equals(version.status())) {
            return reject(422, "PLAN_NOT_APPROVED",
                    "计划版本当前状态 " + version.status() + "，须医师批准后方可模体验证");
        }
        if (!PlanStatus.APPROVED.name().equals(version.status())) {
            return reject(422, "PLAN_ALREADY_VERIFIED",
                    "该计划版本已通过首次模体验证（当前状态 " + version.status() + "），不能重复验证");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        BigDecimal deviation = doseEvaluation.deviationPct(plannedDoseCgy, measuredDoseCgy);
        boolean passed = doseEvaluation.deviationWithinLimit(deviation);

        // 成功与失败均写入验证链；失败不推进版本状态。
        workflowRepository.insertVerification(version.id(), requestId, plannedDoseCgy,
                measuredDoseCgy, deviation, passed, now);

        if (!passed) {
            return reject(422, "VERIFICATION_DEVIATION_EXCEEDED",
                    "模体剂量偏差 " + deviation + "% 超过 3% 限值，不得执行首次治疗");
        }
        planRepository.markVerified(version.id(), now);
        VerificationResponse response = new VerificationResponse(version.id(), version.versionNo(),
                PlanStatus.VERIFIED.name(), requestId, false, plannedDoseCgy, measuredDoseCgy,
                deviation, true);
        return new RecordedResponse(200, response);
    }

    // ============================ 分次执行 ============================

    /**
     * 分次执行记录。按“患者＋计划＋分次号”去重；分次号必须为当前台账数+1，跳次拒绝并告警；
     * 疗程结案后的迟到分次仅登记迟到事件。
     */
    @Transactional
    public RecordedResponse recordFraction(String patientId, String planCode, int fractionNo,
                                           long deliveredDoseCgy, String requestId) {
        ensurePatientLock(patientId);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("patientId", patientId);
        params.put("planCode", planCode);
        params.put("fractionNo", fractionNo);
        params.put("deliveredDoseCgy", deliveredDoseCgy);
        return idempotency.execute(OperationType.FRACTION, requestId, params,
                () -> doRecordFraction(patientId, planCode, fractionNo, deliveredDoseCgy, requestId));
    }

    private RecordedResponse doRecordFraction(String patientId, String planCode, int fractionNo,
                                              long deliveredDoseCgy, String requestId) {
        LocalDateTime now = LocalDateTime.now(clock);
        ActivePlanRow active = planRepository.findActivePlan(patientId).orElse(null);

        if (active == null) {
            return reject(422, "NO_ACTIVE_PLAN", "患者不存在执行中计划，不能登记分次");
        }
        if ("CLOSED".equals(active.status())) {
            // 结案终态：迟到分次只登记迟到事件，不计入台账、不执行投递。归属到实际结案的计划版本。
            String closedPlanCode = active.planCode();
            PlanVersionRow closedVersion = planRepository.findVersionById(active.planVersionId())
                    .orElseThrow(() -> ApiException.notFound("PLAN_VERSION_NOT_FOUND", "结案版本不存在"));
            try {
                workflowRepository.insertLateFraction(patientId, closedPlanCode, closedVersion.id(),
                        fractionNo, requestId, now);
            } catch (DuplicateKeyException e) {
                return reject(409, "LATE_EVENT_DUPLICATE", "迟到分次事件 requestId 已存在：" + requestId);
            }
            LateFractionResponse response = new LateFractionResponse(
                    "LATE_EVENT_RECORDED", patientId, closedPlanCode, closedVersion.id(),
                    closedVersion.versionNo(), fractionNo, requestId, false, Responses.odt(now));
            return new RecordedResponse(200, response);
        }
        if (!active.planCode().equals(planCode)) {
            return reject(409, "ANOTHER_PLAN_ACTIVE",
                    "患者当前执行中计划为 " + active.planCode() + "，与请求计划 " + planCode + " 不一致");
        }

        PlanVersionRow version = planRepository.lockVersionById(active.planVersionId())
                .orElseThrow(() -> ApiException.notFound("PLAN_VERSION_NOT_FOUND", "执行中计划版本不存在"));
        if (PlanStatus.INVALID.name().equals(version.status())
                || PlanStatus.CLOSED.name().equals(version.status())) {
            return reject(422, "PLAN_VERSION_NOT_EXECUTABLE",
                    "计划版本当前状态 " + version.status() + "，不能登记分次");
        }
        if (!PlanStatus.VERIFIED.name().equals(version.status())
                && !PlanStatus.EXECUTING.name().equals(version.status())) {
            return reject(422, "FIRST_VERIFICATION_REQUIRED",
                    "首次治疗前必须通过模体验证（当前状态 " + version.status() + "），不得执行分次");
        }

        int recorded = workflowRepository.countFractions(patientId, planCode);
        int expected = recorded + 1;
        if (fractionNo < expected) {
            // 自然键去重：与 requestId 幂等相互独立，重复分次号直接拒绝，不产生新记录。
            return reject(409, "FRACTION_ALREADY_RECORDED",
                    "分次 " + fractionNo + " 已存在，按患者+计划+分次号去重，拒绝重复登记");
        }
        if (fractionNo > expected) {
            // 跳次：拒绝且告警（alert=true）。
            return new RecordedResponse(409, RejectionBody.alert("FRACTION_SKIP_DETECTED",
                    "跳次执行：下一分次应为第 " + expected + " 次，请求第 " + fractionNo
                            + " 次，拒绝并告警"));
        }

        long fractionId;
        try {
            fractionId = workflowRepository.insertFraction(patientId, planCode, version.id(),
                    fractionNo, deliveredDoseCgy, requestId, now);
        } catch (DuplicateKeyException e) {
            return reject(409, "FRACTION_ALREADY_RECORDED",
                    "分次 " + fractionNo + " 已存在（并发去重），拒绝重复登记");
        }
        planRepository.markExecutingIfFirst(version.id(), now);

        FractionResponse response = new FractionResponse(fractionId, patientId, planCode,
                version.id(), version.versionNo(), fractionNo, deliveredDoseCgy, requestId,
                false, Responses.odt(now));
        return new RecordedResponse(200, response);
    }

    // ============================ 疗程结案 ============================

    /** 疗程结案（终态）。重复结案返回幂等成功；结案后迟到分次只登记迟到事件。 */
    @Transactional
    public RecordedResponse close(String patientId, String planCode, String requestId) {
        ensurePatientLock(patientId);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("patientId", patientId);
        params.put("planCode", planCode);
        return idempotency.execute(OperationType.CLOSURE, requestId, params,
                () -> doClose(patientId, planCode, requestId));
    }

    private RecordedResponse doClose(String patientId, String planCode, String requestId) {
        LocalDateTime now = LocalDateTime.now(clock);
        ActivePlanRow active = planRepository.findActivePlan(patientId).orElse(null);
        if (active == null) {
            return reject(422, "NO_ACTIVE_PLAN", "患者不存在已批准计划，不能结案");
        }
        if ("CLOSED".equals(active.status())) {
            // 终态重复结案：无论是否同 requestId，均返回幂等成功。
            PlanVersionRow closedVersion = planRepository.findVersionById(active.planVersionId())
                    .orElseThrow(() -> ApiException.notFound("PLAN_VERSION_NOT_FOUND", "结案版本不存在"));
            ClosureResponse response = new ClosureResponse(closedVersion.id(),
                    closedVersion.versionNo(), PlanStatus.CLOSED.name(), requestId, true);
            return new RecordedResponse(200, response);
        }
        if (!active.planCode().equals(planCode)) {
            return reject(409, "ANOTHER_PLAN_ACTIVE",
                    "患者当前执行中计划为 " + active.planCode() + "，不能对计划 " + planCode + " 结案");
        }

        PlanVersionRow version = planRepository.lockVersionById(active.planVersionId()).orElseThrow();
        planRepository.markClosed(version.id(), now);
        planRepository.closeActivePlan(patientId, now);
        ClosureResponse response = new ClosureResponse(version.id(), version.versionNo(),
                PlanStatus.CLOSED.name(), requestId, false);
        return new RecordedResponse(200, response);
    }

    // ============================ 复算与台账查询 ============================

    /** 物理审核复算：按模板限值与申报值实时复算逐项结果，并与审核通过时的存储快照逐项对比。 */
    @Transactional(readOnly = true)
    public AuditRecomputation recomputeAudit(String patientId, String planCode, int versionNo) {
        PlanVersionRow version = getVersion(patientId, planCode, versionNo);
        List<ConstraintItemRow> templateItems =
                directoryRepository.findConstraintItems(version.templateCode());
        List<OarDoseRow> plannedDoses = planRepository.findOarDoses(version.id());
        DoseEvaluation.CheckResult recomputed = doseEvaluation.evaluate(templateItems, plannedDoses);

        AuditRow auditRow = workflowRepository.findAuditByVersion(version.id()).orElse(null);
        List<AuditItemRow> storedItems = auditRow == null
                ? List.of()
                : workflowRepository.findAuditItems(auditRow.id());
        boolean matchesSnapshot = auditRow != null && snapshotMatches(storedItems, recomputed.items());
        return new AuditRecomputation(version, auditRow != null, recomputed.items(),
                storedItems.size(), matchesSnapshot);
    }

    private boolean snapshotMatches(List<AuditItemRow> stored, List<ConstraintCheckItem> recomputed) {
        if (stored.size() != recomputed.size()) {
            return false;
        }
        for (int i = 0; i < stored.size(); i++) {
            AuditItemRow s = stored.get(i);
            ConstraintCheckItem r = recomputed.get(i);
            if (!s.organCode().equals(r.organCode())
                    || !s.metricType().equals(r.metricType())
                    || s.withinLimit() != r.withinLimit()
                    || s.plannedValue().compareTo(r.plannedValue()) != 0
                    || s.limitValue().compareTo(r.limitValue()) != 0) {
                return false;
            }
        }
        return true;
    }

    /** 模体验证链（含失败记录）。 */
    @Transactional(readOnly = true)
    public List<VerificationRow> verificationChain(String patientId, String planCode, int versionNo) {
        PlanVersionRow version = getVersion(patientId, planCode, versionNo);
        return workflowRepository.findVerifications(version.id());
    }

    /** 分次台账（按分次号升序，同计划跨版本连续编号）。 */
    @Transactional(readOnly = true)
    public List<FractionRow> fractionLedger(String patientId, String planCode) {
        return workflowRepository.findFractions(patientId, planCode);
    }

    /** 结案后迟到分次事件列表。 */
    @Transactional(readOnly = true)
    public List<WorkflowRepository.LateFractionRow> lateFractions(String patientId, String planCode) {
        return workflowRepository.findLateFractions(patientId, planCode);
    }

    /** 计划全部版本视图。 */
    @Transactional(readOnly = true)
    public List<PlanVersionRow> versions(String patientId, String planCode) {
        return planRepository.findVersions(patientId, planCode);
    }

    // ============================ 内部辅助 ============================

    private RecordedResponse reject(int httpStatus, String code, String message) {
        boolean alert = "FRACTION_SKIP_DETECTED".equals(code);
        return new RecordedResponse(httpStatus, new RejectionBody(code, message, alert, List.of()));
    }

    private Map<String, Object> baseParams(String patientId, String planCode, int versionNo) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("patientId", patientId);
        params.put("planCode", planCode);
        params.put("versionNo", versionNo);
        return params;
    }

    private void ensurePatientLock(String patientId) {
        if (!planRepository.lockPatient(patientId)) {
            throw ApiException.notFound("PATIENT_NOT_FOUND", "患者不存在：" + patientId);
        }
    }

    private PlanVersionRow lockVersion(String patientId, String planCode, int versionNo) {
        return planRepository.lockVersion(patientId, planCode, versionNo)
                .orElseThrow(() -> ApiException.notFound("PLAN_VERSION_NOT_FOUND",
                        "计划版本不存在：" + planCode + "#v" + versionNo));
    }

    private PlanVersionRow getVersion(String patientId, String planCode, int versionNo) {
        return planRepository.findVersion(patientId, planCode, versionNo)
                .orElseThrow(() -> ApiException.notFound("PLAN_VERSION_NOT_FOUND",
                        "计划版本不存在：" + planCode + "#v" + versionNo));
    }

    /** 审核复算聚合结果。 */
    public record AuditRecomputation(
            PlanVersionRow version,
            boolean auditStored,
            List<ConstraintCheckItem> recomputedItems,
            int storedItemCount,
            boolean snapshotMatches) {
    }
}
