package com.example.starter.rt.web;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.rt.dto.ApprovalRequest;
import com.example.starter.rt.dto.AuditRequest;
import com.example.starter.rt.dto.AuditResponse;
import com.example.starter.rt.dto.ClosureRequest;
import com.example.starter.rt.dto.ConstraintCheckItem;
import com.example.starter.rt.dto.CreateDeviceRequest;
import com.example.starter.rt.dto.CreatePatientRequest;
import com.example.starter.rt.dto.CreatePlanRequest;
import com.example.starter.rt.dto.CreateTemplateRequest;
import com.example.starter.rt.dto.DeviceCapabilityRequest;
import com.example.starter.rt.dto.DeviceOccupancyItem;
import com.example.starter.rt.dto.DeviceResponse;
import com.example.starter.rt.dto.FractionLedgerItem;
import com.example.starter.rt.dto.FractionRequest;
import com.example.starter.rt.dto.LateFractionItem;
import com.example.starter.rt.dto.PlanVersionResponse;
import com.example.starter.rt.dto.RecordedResponse;
import com.example.starter.rt.dto.ScheduleBatchResponse;
import com.example.starter.rt.dto.ScheduleCancelRequest;
import com.example.starter.rt.dto.SchedulePublishRequest;
import com.example.starter.rt.dto.TreatmentRequest;
import com.example.starter.rt.dto.VerificationRequest;
import com.example.starter.rt.repository.WorkflowRepository.FractionRow;
import com.example.starter.rt.repository.WorkflowRepository.LateFractionRow;
import com.example.starter.rt.repository.WorkflowRepository.VerificationRow;
import com.example.starter.rt.service.PlanAdminService;
import com.example.starter.rt.service.Responses;
import com.example.starter.rt.service.ScheduleService;
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
    private final ScheduleService scheduleService;

    public RadiotherapyController(PlanAdminService adminService, WorkflowService workflowService,
                                  ScheduleService scheduleService) {
        this.adminService = adminService;
        this.workflowService = workflowService;
        this.scheduleService = scheduleService;
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

    // ---------------- 设备与分次排程 ----------------

    /** 登记合成设备（可执行复杂度等级上限 1 至 3，初始版本 1）。 */
    @PostMapping("/devices")
    public ResponseEntity<DeviceResponse> createDevice(@Valid @RequestBody CreateDeviceRequest request) {
        return ResponseEntity.status(201).body(scheduleService.createDevice(request));
    }

    /** 设备能力变更（expectedVersion 乐观锁，成功变更加 1；下调只阻断尚未开始项）。 */
    @PutMapping("/devices/{deviceId}/capability")
    public ResponseEntity<Object> changeCapability(@PathVariable String deviceId,
                                                   @Valid @RequestBody DeviceCapabilityRequest request) {
        return toEntity(scheduleService.changeCapability(deviceId, request));
    }

    /** 连续分次批量排程发布：冻结计划版本/等级/设备/窗口，原子占用，任一冲突整批零占用。 */
    @PostMapping("/schedules")
    public ResponseEntity<Object> publishSchedule(@Valid @RequestBody SchedulePublishRequest request) {
        return toEntity(scheduleService.publish(request));
    }

    /** 排程批次取消（expectedVersion 乐观锁）：释放全部未开始占用。 */
    @PostMapping("/schedules/{batchId}/cancel")
    public ResponseEntity<Object> cancelSchedule(@PathVariable long batchId,
                                                 @Valid @RequestBody ScheduleCancelRequest request) {
        return toEntity(scheduleService.cancel(batchId, request));
    }

    /** 受控治疗记录入口：须在排程窗口内且匹配排程/计划版本，完成原子释放占用并冻结实绩。 */
    @PostMapping("/treatments")
    public ResponseEntity<Object> treat(@Valid @RequestBody TreatmentRequest request) {
        return toEntity(scheduleService.treat(request));
    }

    /** 设备占用查询：可选 UTC 区间过滤，含已释放明细便于核对。 */
    @GetMapping("/devices/{deviceId}/occupancy")
    public List<DeviceOccupancyItem> occupancy(@PathVariable String deviceId,
                                               @RequestParam(required = false) String from,
                                               @RequestParam(required = false) String to) {
        return scheduleService.occupancy(deviceId, parseUtc("from", from), parseUtc("to", to));
    }

    /** 计划排程批次列表（含明细）。 */
    @GetMapping("/patients/{patientId}/plans/{planCode}/schedules")
    public List<ScheduleBatchResponse> schedulesOfPlan(@PathVariable String patientId,
                                                       @PathVariable String planCode) {
        return scheduleService.schedulesOfPlan(patientId, planCode);
    }

    private Instant parseUtc(String param, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new ApiException(400, "VALIDATION_FAILED",
                    "查询参数 " + param + " 须为 UTC ISO-8601 时刻（如 2026-09-28T10:00:00Z）：" + value);
        }
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
