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

    /** 联检创建请求；项目至少两种且唯一 */
    public record JointCreateRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "批次 ID 不能为空") Long batchId,
            @NotNull(message = "快检记录 ID 不能为空") Long testId,
            @NotBlank(message = "承检机构不能为空") String agencyCode,
            @NotNull(message = "项目列表不能为空")
            @Size(min = 2, message = "联检项目至少两种") List<@NotBlank(message = "项目不能为空") String> items) {
    }

    /** 联检项目快照视图；limitValue 单位 mg/kg */
    public record JointItemView(
            String item,
            BigDecimal limitValue,
            Integer capabilityVersion,
            String sampleNo) {
    }

    /** 联检创建响应 */
    public record JointResponse(
            Long jointId,
            String requestId,
            Long batchId,
            Long testId,
            String agencyCode,
            String status,
            Integer version,
            List<JointItemView> items,
            Instant createdAt,
            boolean replayed) {
    }

    /** 分样条目：项目与样本号 */
    public record JointAllocEntry(
            @NotBlank(message = "项目不能为空") String item,
            @NotBlank(message = "样本号不能为空") String sampleNo) {
    }

    /** 分样请求；须覆盖联检全部项目，样本号全局唯一 */
    public record JointAllocateRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "expectedVersion 不能为空") @Min(value = 1, message = "expectedVersion 必须大于等于 1") Integer expectedVersion,
            @NotNull(message = "分样列表不能为空")
            @Size(min = 2, message = "分样至少覆盖两个项目") List<@Valid JointAllocEntry> allocations) {
    }

    /** 分样响应 */
    public record JointAllocateResponse(
            Long jointId,
            String status,
            Integer version,
            List<JointAllocEntry> allocations,
            Instant allocatedAt,
            boolean replayed) {
    }

    /** 联检结果录入/复测请求；浓度单位 mg/kg，有限非负；testedAt 为检测精确时刻（UTC） */
    public record JointResultRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "expectedVersion 不能为空") @Min(value = 1, message = "expectedVersion 必须大于等于 1") Integer expectedVersion,
            @NotBlank(message = "项目不能为空") String item,
            @NotBlank(message = "样本号不能为空") String sampleNo,
            @NotNull(message = "浓度不能为空") @DecimalMin(value = "0", message = "浓度不能为负") BigDecimal concentration,
            @NotNull(message = "检测时刻不能为空") Instant testedAt) {
    }

    /** 联检结果响应；supplemental=true 表示已综合判定，仅记补充信息不重开 */
    public record JointResultResponse(
            Long resultId,
            String requestId,
            Long jointId,
            String item,
            String sampleNo,
            BigDecimal concentration,
            Integer resultVersion,
            Integer jointVersion,
            String jointStatus,
            Instant createdAt,
            boolean replayed,
            boolean supplemental) {
    }

    /** 项版本条目：综合判定携带的该项当前结果版本 */
    public record JointItemVersionEntry(
            @NotBlank(message = "项目不能为空") String item,
            @NotNull(message = "结果版本不能为空") @Min(value = 1, message = "结果版本必须大于等于 1") Integer version) {
    }

    /** 综合判定请求；itemVersions 须与全部项当前结果版本一致，失配则判定零写入 */
    public record JointDecisionRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "expectedVersion 不能为空") @Min(value = 1, message = "expectedVersion 必须大于等于 1") Integer expectedVersion,
            @NotNull(message = "项版本集合不能为空")
            @Size(min = 2, message = "项版本至少覆盖两个项目") List<@Valid JointItemVersionEntry> itemVersions) {
    }

    /** 综合判定响应；retentionUntil 仅不合格转处置时非空（UTC，结案 + 48 小时） */
    public record JointDecisionResponse(
            Long jointId,
            String verdict,
            Integer jointVersion,
            String batchStatus,
            Instant retentionUntil,
            String evidence,
            Instant decidedAt,
            boolean replayed) {
    }

    /** 联检结果视图 */
    public record JointResultView(
            String item,
            String sampleNo,
            BigDecimal concentration,
            Integer version,
            Instant createdAt) {
    }

    /** 联检判定视图 */
    public record JointDecisionView(
            String verdict,
            String evidence,
            Instant decidedAt) {
    }

    /** 联检单详情 */
    public record JointDetailResponse(
            Long jointId,
            Long batchId,
            Long testId,
            String agencyCode,
            String status,
            Integer version,
            List<JointItemView> items,
            List<JointResultView> results,
            JointDecisionView decision,
            Instant createdAt) {
    }
}
