package com.example.starter.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 对外 API 请求/响应载体。时间均为应用时钟本地时间（ISO-8601）。
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    /** 巡检登记请求。 */
    public record RegisterInspectionRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotNull Long bladeId,
            @NotBlank @Size(max = 128) String position,
            @NotBlank @Size(max = 64) String inspector) {
    }

    /** 巡检登记响应。 */
    public record InspectionResponse(
            long id,
            long bladeId,
            String position,
            String inspector,
            LocalDateTime inspectedAt) {
    }

    /** 缺陷登记请求。 */
    public record RegisterDefectRequest(
            @NotBlank @Size(max = 64) String requestId,
            Long inspectionId,
            @NotNull Long bladeId,
            @NotBlank @Size(max = 128) String position,
            @NotBlank @Size(max = 64) String defectType,
            @DecimalMin("0.0") BigDecimal lengthMm,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal depthRatio) {
    }

    /** 缺陷登记响应：含分级结果及联动产生的限功率/根因评估标识。 */
    public record DefectResponse(
            long id,
            long bladeId,
            String position,
            String defectType,
            String level,
            String status,
            LocalDateTime registeredAt,
            Long curtailmentId,
            Long evaluationId) {
    }

    /** 缺陷详情响应。 */
    public record DefectDetailResponse(
            long id,
            long bladeId,
            String position,
            String defectType,
            String level,
            String status,
            LocalDateTime registeredAt,
            LocalDateTime closedAt,
            List<EscalationView> escalations) {
    }

    /** 创建维修单请求。 */
    public record CreateRepairOrderRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotNull Long defectId) {
    }

    /** 维修单响应。 */
    public record RepairOrderResponse(
            long id,
            long defectId,
            long bladeId,
            String status,
            LocalDateTime deadlineAt,
            LocalDateTime createdAt,
            LocalDateTime closedAt) {
    }

    /** 维修记录登记请求。 */
    public record AddRepairRecordRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotBlank @Size(max = 64) String technician,
            @NotBlank @Size(max = 512) String description) {
    }

    /** 维修记录响应。 */
    public record RepairRecordResponse(
            long id,
            long repairOrderId,
            String technician,
            String description,
            LocalDateTime repairedAt) {
    }

    /** 复检登记请求。 */
    public record ReinspectRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotBlank @Pattern(regexp = "PASS|FAIL") String result,
            @NotBlank @Size(max = 64) String inspector) {
    }

    /** 复检响应：合格时维修单结案、缺陷闭环、限功率同事务撤销。 */
    public record ReinspectResponse(
            long id,
            long repairOrderId,
            String result,
            String orderStatus,
            String defectStatus,
            boolean curtailmentLifted,
            boolean escalated) {
    }

    /** 完成根因评估请求。 */
    public record CompleteEvaluationRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotBlank @Size(max = 512) String conclusion) {
    }

    /** 根因评估响应。 */
    public record EvaluationResponse(
            long id,
            long bladeId,
            String defectType,
            long triggerDefectId,
            String status,
            LocalDateTime triggeredAt,
            LocalDateTime completedAt,
            String conclusion) {
    }

    /** 限功率链节点。 */
    public record CurtailmentView(
            long id,
            long bladeId,
            String reason,
            Long sourceDefectId,
            Long sourceEvaluationId,
            LocalDateTime startedAt,
            LocalDateTime liftedAt) {
    }

    /** 叶片限功率状态。 */
    public record BladeStatusResponse(
            long bladeId,
            boolean curtailed,
            List<CurtailmentView> activeCurtailments) {
    }

    /** 逾期升级告警视图。 */
    public record EscalationView(
            long id,
            long defectId,
            LocalDateTime firstRegisteredAt,
            LocalDateTime escalatedAt,
            String message) {
    }

    /** 维修台账：维修单 + 维修记录 + 复检记录。 */
    public record RepairLedgerView(
            long orderId,
            long defectId,
            String status,
            LocalDateTime deadlineAt,
            LocalDateTime createdAt,
            LocalDateTime closedAt,
            List<RepairRecordResponse> repairRecords,
            List<ReinspectView> reinspections) {
    }

    /** 复检记录视图。 */
    public record ReinspectView(
            long id,
            String result,
            String inspector,
            LocalDateTime inspectedAt) {
    }

    /** 单类缺陷复发统计。 */
    public record RecurrenceTypeStats(
            String defectType,
            int totalCount,
            List<RecurrenceDefectPoint> defects,
            boolean evaluationTriggered,
            Long evaluationId) {
    }

    /** 复发统计中的单次缺陷点：含 90 日窗口内累计次数。 */
    public record RecurrenceDefectPoint(
            long defectId,
            LocalDateTime registeredAt,
            int windowCount,
            boolean triggerPoint) {
    }

    /** 叶片复发统计响应。 */
    public record RecurrenceStatsResponse(
            long bladeId,
            List<RecurrenceTypeStats> stats) {
    }

    /** 统一错误响应。 */
    public record ErrorResponse(String error, String message) {
    }
}
