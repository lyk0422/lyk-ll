package com.example.starter.pesticide.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * API 请求与响应模型集合。
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    /** 产地报备请求 */
    public record ReportRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "产地不能为空") String origin,
            @NotBlank(message = "品种不能为空") String variety,
            @NotBlank(message = "批次号不能为空") String batchNo,
            @NotNull(message = "数量不能为空") @Positive(message = "数量必须为正") BigDecimal quantity) {
    }

    /** 产地报备响应 */
    public record ReportResponse(
            Long batchId,
            String requestId,
            String origin,
            String variety,
            String batchNo,
            BigDecimal quantity,
            String status,
            String samplingLevel,
            Instant createdAt,
            boolean replayed) {
    }

    /** 抽样请求 */
    public record SampleRequest(
            @NotBlank(message = "检测项目不能为空") String item) {
    }

    /** 抽样响应 */
    public record SampleResponse(
            Long sampleId,
            Long batchId,
            String item,
            Instant createdAt) {
    }

    /** 快检请求；抑制率单位 %，达到 50 判疑似 */
    public record TestRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "批次 ID 不能为空") Long batchId,
            @NotBlank(message = "检测项目不能为空") String item,
            @NotNull(message = "抑制率不能为空")
            @DecimalMin(value = "0", message = "抑制率不能小于 0")
            @DecimalMax(value = "100", message = "抑制率不能大于 100") BigDecimal inhibitionRate) {
    }

    /** 快检响应；late=true 表示批次已终态，仅记录迟到事件 */
    public record TestResponse(
            Long testId,
            String requestId,
            Long batchId,
            String item,
            BigDecimal inhibitionRate,
            String result,
            String batchStatus,
            Instant createdAt,
            boolean replayed,
            boolean late) {
    }

    /** 定量复检请求 */
    public record RecheckRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "批次 ID 不能为空") Long batchId,
            @NotBlank(message = "复检项目不能为空") String item,
            @NotNull(message = "复检结论不能为空") Boolean qualified) {
    }

    /** 复检响应；retentionUntil 仅不合格转处置时非空（UTC，结案 + 48 小时） */
    public record RecheckResponse(
            Long recheckId,
            String requestId,
            Long batchId,
            String item,
            Boolean qualified,
            String batchStatus,
            Instant retentionUntil,
            Instant createdAt,
            boolean replayed,
            boolean late) {
    }

    /** 判定放行响应 */
    public record ReleaseResponse(
            Long batchId,
            String status,
            Long admissionId,
            Instant releasedAt,
            boolean replayed) {
    }

    /** 快检记录视图 */
    public record TestView(
            Long testId,
            String item,
            BigDecimal inhibitionRate,
            String result,
            Instant createdAt) {
    }

    /** 迟到事件视图 */
    public record LateEventView(
            Long eventId,
            String eventType,
            String payload,
            Instant createdAt) {
    }

    /** 批次详情 */
    public record BatchDetailResponse(
            Long batchId,
            String origin,
            String variety,
            String batchNo,
            BigDecimal quantity,
            String status,
            String samplingLevel,
            Instant createdAt,
            List<TestView> tests,
            List<LateEventView> lateEvents,
            Instant retentionUntil) {
    }

    /** 产地预警台账条目 */
    public record AlertResponse(
            Long alertId,
            String origin,
            String variety,
            LocalDate alertDate,
            Integer triggerCount,
            String status,
            Instant createdAt) {
    }

    /** 联检建单请求；occurredAt 为请求精确时刻（UTC 瞬时），与集合一并参与幂等指纹 */
    public record JointOrderCreateRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "批次 ID 不能为空") Long batchId,
            @NotBlank(message = "检验机构编码不能为空") String labCode,
            @NotNull(message = "检测项目集合不能为空") @Size(min = 2, message = "联检项目至少两种")
            List<@NotBlank(message = "项目编码不能为空") String> itemCodes,
            @NotNull(message = "请求时刻不能为空") Instant occurredAt) {
    }

    /** 联检单项目视图（建单时冻结的名称/单位/限值） */
    public record JointItemView(
            String itemCode,
            String itemName,
            String unit,
            BigDecimal limitValue) {
    }

    /** 联检建单响应 */
    public record JointOrderResponse(
            Long orderId,
            String requestId,
            Long batchId,
            String labCode,
            String labName,
            String labCapabilityVersion,
            String catalogVersion,
            List<JointItemView> items,
            String status,
            Integer version,
            Instant createdAt,
            boolean replayed) {
    }

    /** 分样项：项目编码 + 全局唯一样本号 */
    public record JointAssignItem(
            @NotBlank(message = "项目编码不能为空") String itemCode,
            @NotBlank(message = "样本号不能为空") String sampleNo) {
    }

    /** 联检分样请求：须一次覆盖联检单全部项目 */
    public record JointAssignRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "expectedVersion 不能为空")
            @Min(value = 1, message = "expectedVersion 从 1 开始") Integer expectedVersion,
            @NotNull(message = "分样集合不能为空") @Size(min = 1, message = "分样集合不能为空")
            List<@Valid JointAssignItem> items,
            @NotNull(message = "请求时刻不能为空") Instant occurredAt) {
    }

    /** 分样记录视图 */
    public record JointAssignmentView(
            Long assignmentId,
            String itemCode,
            String sampleNo,
            Instant createdAt) {
    }

    /** 联检分样响应 */
    public record JointAssignResponse(
            Long orderId,
            String status,
            Integer version,
            List<JointAssignmentView> assignments,
            boolean replayed) {
    }

    /** 联检结果/复测请求：浓度有限非负，恰等限值合格 */
    public record JointResultRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "expectedVersion 不能为空")
            @Min(value = 1, message = "expectedVersion 从 1 开始") Integer expectedVersion,
            @NotBlank(message = "项目编码不能为空") String itemCode,
            @NotNull(message = "浓度不能为空")
            @DecimalMin(value = "0", message = "浓度不能为负") BigDecimal concentration,
            @NotNull(message = "请求时刻不能为空") Instant occurredAt) {
    }

    /** 联检结果响应；supplemental=true 表示综合判定后仅记补充信息，不重开判定 */
    public record JointResultResponse(
            Long resultId,
            Long orderId,
            String itemCode,
            String sampleNo,
            BigDecimal concentration,
            Boolean qualified,
            Integer itemVersion,
            Integer orderVersion,
            String status,
            boolean supplemental,
            boolean replayed) {
    }

    /** 综合判定请求 */
    public record JointJudgeRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "expectedVersion 不能为空")
            @Min(value = 1, message = "expectedVersion 从 1 开始") Integer expectedVersion,
            @NotNull(message = "请求时刻不能为空") Instant occurredAt) {
    }

    /** 综合判定响应；retentionUntil 仅不合格转处置时非空（UTC，结案 + 48 小时） */
    public record JointJudgeResponse(
            Long judgmentId,
            Long orderId,
            String verdict,
            String itemVersions,
            Integer version,
            Long batchId,
            String batchStatus,
            Instant retentionUntil,
            Instant judgedAt,
            boolean replayed) {
    }

    /** 判定后补充信息请求；itemCode 可空表示整单补充 */
    public record JointSupplementRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            String itemCode,
            @NotBlank(message = "补充内容不能为空") String note,
            @NotNull(message = "请求时刻不能为空") Instant occurredAt) {
    }

    /** 补充信息响应 */
    public record JointSupplementResponse(
            Long supplementId,
            Long orderId,
            boolean replayed) {
    }

    /** 联检结果视图（含被复测替换的历史版本） */
    public record JointResultView(
            Long resultId,
            String itemCode,
            String sampleNo,
            BigDecimal concentration,
            Boolean qualified,
            Integer itemVersion,
            Integer orderVersion,
            Boolean superseded,
            Instant createdAt) {
    }

    /** 综合判定视图（含冻结证据） */
    public record JointJudgmentView(
            Long judgmentId,
            String verdict,
            String itemVersions,
            String evidence,
            String evidenceFingerprint,
            Instant judgedAt) {
    }

    /** 补充信息视图 */
    public record JointSupplementView(
            Long supplementId,
            String itemCode,
            String note,
            Instant createdAt) {
    }

    /** 联检单详情 */
    public record JointOrderDetailResponse(
            Long orderId,
            Long batchId,
            String labCode,
            String labName,
            String labCapabilityVersion,
            String catalogVersion,
            String frozenTests,
            String status,
            Integer version,
            Instant judgedAt,
            Instant createdAt,
            List<JointItemView> items,
            List<JointAssignmentView> assignments,
            List<JointResultView> results,
            JointJudgmentView judgment,
            List<JointSupplementView> supplements) {
    }
}
