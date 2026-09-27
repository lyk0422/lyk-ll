package com.example.starter.rt.web;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.rt.dto.ApprovalRequest;
import com.example.starter.rt.dto.AuditRequest;
import com.example.starter.rt.dto.AuditResponse;
import com.example.starter.rt.dto.ClosureRequest;
import com.example.starter.rt.dto.ConstraintCheckItem;
import com.example.starter.rt.dto.CreatePatientRequest;
import com.example.starter.rt.dto.CreatePlanRequest;
import com.example.starter.rt.dto.CreateTemplateRequest;
import com.example.starter.rt.dto.DeviceOccupancyItem;
import com.example.starter.rt.dto.DeviceRegisterRequest;
import com.example.starter.rt.dto.DeviceUpdateRequest;
import com.example.starter.rt.dto.FractionLedgerItem;
import com.example.starter.rt.dto.FractionRequest;
import com.example.starter.rt.dto.LateFractionItem;
import com.example.starter.rt.dto.PlanVersionResponse;
import com.example.starter.rt.dto.RecordedResponse;
import com.example.starter.rt.dto.ScheduleCancelRequest;
import com.example.starter.rt.dto.SchedulePublishRequest;
import com.example.starter.rt.dto.ScheduleResponse;
import com.example.starter.rt.dto.TreatmentRequest;
import com.example.starter.rt.dto.VerificationRequest;
import com.example.starter.rt.repository.WorkflowRepository.FractionRow;
import com.example.starter.rt.repository.WorkflowRepository.LateFractionRow;
import com.example.starter.rt.repository.WorkflowRepository.VerificationRow;
import com.example.starter.rt.service.PlanAdminService;
import com.example.starter.rt.service.Responses;
import com.example.starter.rt.service.SchedulingService;
import com.example.starter.rt.service.WorkflowService;

import jakarta.validation.Valid;

/**
 * 放疗计划剂量审核与分次执行核对服务 REST 入口。
 */
@RestController
@RequestMapping("/api/radiotherapy")
public class RadiotherapyController {

    private final PlanAdminService adminService;
    private final WorkflowService workflowService;
    private final SchedulingService schedulingService;

    public RadiotherapyController(PlanAdminService adminService, WorkflowService workflowService,
                                  SchedulingService schedulingService) {
        this.adminService = adminService;
        this.workflowService = workflowService;
        this.schedulingService = schedulingService;
    }

    // ---------------- 合成目录与计划设计 ----------------

    @PostMapping("/patients")
    public ResponseEntity<Void> createPatient(@Valid @RequestBody CreatePatientRequest request) {
        adminService.createPatient(request);
        return ResponseEntity.status(201).build();
    }

    @PostMapping("/constraint-templates")
    public ResponseEntity<Void> createTemplate(@Valid @RequestBody CreateTemplateRequest request) {
        adminService.createTemplate(request);
        return ResponseEntity.status(201).build();
    }

    @PostMapping("/plans")
    public ResponseEntity<PlanVersionResponse> createPlan(@Valid @RequestBody CreatePlanRequest request) {
        return ResponseEntity.status(201).body(adminService.createPlan(request));
    }

    @GetMapping("/patients/{patientId}/plans/{planCode}/versions")
    public List<PlanVersionResponse> versions(@PathVariable String patientId,
                                              @PathVariable String planCode) {
        return workflowService.versions(patientId, planCode).stream().map(Responses::toPlanVersion).toList();
    }

    // ---------------- 审核 / 批准 / 验证 / 分次 / 结案 ----------------

    @PostMapping("/audits")
    public ResponseEntity<Object> audit(@Valid @RequestBody AuditRequest request) {
        return toEntity(workflowService.audit(request.patientId(), request.planCode(),
                request.versionNo(), request.requestId()));
    }

    @PostMapping("/approvals")
    public ResponseEntity<Object> approve(@Valid @RequestBody ApprovalRequest request) {
        return toEntity(workflowService.approve(request.patientId(), request.planCode(),
                request.versionNo(), request.requestId()));
    }

    @PostMapping("/verifications")
    public ResponseEntity<Object> verify(@Valid @RequestBody VerificationRequest request) {
        return toEntity(workflowService.verify(request.patientId(), request.planCode(),
                request.versionNo(), request.plannedDoseCgy(), request.measuredDoseCgy(),
                request.requestId()));
    }

    @PostMapping("/fractions")
    public ResponseEntity<Object> recordFraction(@Valid @RequestBody FractionRequest request) {
        return toEntity(workflowService.recordFraction(request.patientId(), request.planCode(),
                request.fractionNo(), request.deliveredDoseCgy(), request.requestId()));
    }

    @PostMapping("/closures")
    public ResponseEntity<Object> close(@Valid @RequestBody ClosureRequest request) {
        return toEntity(workflowService.close(request.patientId(), request.planCode(),
                request.requestId()));
    }

    // ---------------- 设备时长配额与排程门禁 ----------------

    /** 合成设备登记（版本从 1 开始）。 */
    @PostMapping("/devices")
    public ResponseEntity<Object> registerDevice(@Valid @RequestBody DeviceRegisterRequest request) {
        return toEntity(schedulingService.registerDevice(request));
    }

    /** 设备能力变更（携带 expectedVersion；下调只阻断尚未开始项）。 */
    @PostMapping("/devices/{deviceId}/capability")
    public ResponseEntity<Object> updateDevice(@PathVariable String deviceId,
                                               @Valid @RequestBody DeviceUpdateRequest request) {
        return toEntity(schedulingService.updateDevice(deviceId, request));
    }

    /** 设备当前占用查询（占用中的排程项）。 */
    @GetMapping("/devices/{deviceId}/occupancy")
    public List<DeviceOccupancyItem> occupancy(@PathVariable String deviceId) {
        return schedulingService.occupancy(deviceId);
    }

    /** 排程批量发布：冻结计划版本/等级/设备/窗口，任一冲突整批零占用。 */
    @PostMapping("/schedules")
    public ResponseEntity<Object> publishSchedule(@Valid @RequestBody SchedulePublishRequest request) {
        return toEntity(schedulingService.publish(request));
    }

    /** 排程取消：未开始项释放占用，已完成项保留实绩。 */
    @PostMapping("/schedules/{scheduleId}/cancellation")
    public ResponseEntity<Object> cancelSchedule(@PathVariable long scheduleId,
                                                 @Valid @RequestBody ScheduleCancelRequest request) {
        return toEntity(schedulingService.cancel(scheduleId, request));
    }

    /** 患者+计划的排程批次查询。 */
    @GetMapping("/patients/{patientId}/plans/{planCode}/schedules")
    public List<ScheduleResponse> schedules(@PathVariable String patientId,
                                            @PathVariable String planCode) {
        return schedulingService.schedules(patientId, planCode);
    }

    /** 受控治疗记录：须在排程窗口内、匹配排程/计划版本、遵守顺序分次规则。 */
    @PostMapping("/treatments")
    public ResponseEntity<Object> treat(@Valid @RequestBody TreatmentRequest request) {
        return toEntity(schedulingService.treat(request));
    }

    // ---------------- 复算与台账 ----------------

    /** 物理审核逐项复算结果，并与审核快照核对是否一致。 */
    @GetMapping("/patients/{patientId}/plans/{planCode}/versions/{versionNo}/audit-recomputation")
    public AuditRecomputationView recomputeAudit(@PathVariable String patientId,
                                                 @PathVariable String planCode,
                                                 @PathVariable int versionNo) {
        WorkflowService.AuditRecomputation result =
                workflowService.recomputeAudit(patientId, planCode, versionNo);
        return new AuditRecomputationView(
                Responses.toPlanVersion(result.version()),
                result.auditStored(),
                result.recomputedItems(),
                result.storedItemCount(),
                result.snapshotMatches());
    }

    /** 模体验证链（成功与失败均含）。 */
    @GetMapping("/patients/{patientId}/plans/{planCode}/versions/{versionNo}/verifications")
    public List<VerificationRow> verificationChain(@PathVariable String patientId,
                                                   @PathVariable String planCode,
                                                   @PathVariable int versionNo) {
        return workflowService.verificationChain(patientId, planCode, versionNo);
    }

    /** 分次执行台账（按分次号升序）。 */
    @GetMapping("/patients/{patientId}/plans/{planCode}/fractions")
    public List<FractionLedgerItem> fractionLedger(@PathVariable String patientId,
                                                   @PathVariable String planCode) {
        return workflowService.fractionLedger(patientId, planCode).stream()
                .map(this::toLedgerItem).toList();
    }

    /** 结案后迟到分次事件。 */
    @GetMapping("/patients/{patientId}/plans/{planCode}/late-fractions")
    public List<LateFractionItem> lateFractions(@PathVariable String patientId,
                                                @PathVariable String planCode) {
        return workflowService.lateFractions(patientId, planCode).stream()
                .map(this::toLateItem).toList();
    }

    private FractionLedgerItem toLedgerItem(FractionRow row) {
        return new FractionLedgerItem(row.id(), row.planVersionId(),
                workflowService.versions(row.patientId(), row.planCode()).stream()
                        .filter(v -> v.id() == row.planVersionId())
                        .findFirst().map(v -> v.versionNo()).orElse(null),
                row.fractionNo(), row.deliveredDoseCgy(), row.requestId(),
                Responses.odt(row.executedAt()));
    }

    private LateFractionItem toLateItem(LateFractionRow row) {
        Integer versionNo = workflowService.versions(row.patientId(), row.planCode()).stream()
                .filter(v -> v.id() == row.planVersionId())
                .findFirst().map(v -> v.versionNo()).orElse(null);
        return new LateFractionItem(row.id(), row.planVersionId(), versionNo, row.fractionNo(),
                row.requestId(), Responses.odt(row.createdAt()));
    }

    private ResponseEntity<Object> toEntity(RecordedResponse result) {
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }

    /** 审核复算视图。 */
    public record AuditRecomputationView(
            PlanVersionResponse version,
            boolean auditStored,
            List<ConstraintCheckItem> recomputedItems,
            int storedItemCount,
            boolean snapshotMatches) {
    }
}
