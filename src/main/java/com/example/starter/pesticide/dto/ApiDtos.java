package com.example.starter.pesticide.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
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

    /** 联检创建请求；occurredAt 为精确时刻（UTC），入幂等指纹 */
    public record JointTestCreateRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "批次 ID 不能为空") Long batchId,
            @NotBlank(message = "承检机构不能为空") String agencyCode,
            @NotNull(message = "项目集合不能为空")
            @Size(min = 2, message = "联检项目至少两种") List<@NotBlank(message = "项目不能为空") String> items,
            @NotNull(message = "精确时刻不能为空") Instant occurredAt) {
    }

    /** 联检项目视图；限值单位 mg/kg，恰等限值判合格 */
    public record JointTestItemView(
            String item,
            BigDecimal limitValue,
            Integer capabilityVersion) {
    }

    /** 联检创建响应；版本从 1 开始 */
    public record JointTestCreateResponse(
            Long orderId,
            String requestId,
            Long batchId,
            String agencyCode,
            Integer version,
            String status,
            List<JointTestItemView> items,
            Instant createdAt,
            boolean replayed) {
    }

    /** 分样明细：项目 + 全局唯一样本号 */
    public record JointTestSplitItem(
            @NotBlank(message = "项目不能为空") String item,
            @NotBlank(message = "样本号不能为空") String sampleNo) {
    }

    /** 联检分样请求；须覆盖全部项目，任一项失败整单不分样 */
    public record JointTestSplitRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "期望版本不能为空") @Positive(message = "期望版本必须为正") Integer expectedVersion,
            @NotNull(message = "分样集合不能为空")
            @Size(min = 1, message = "分样集合不能为空") List<@Valid JointTestSplitItem> splits,
            @NotNull(message = "精确时刻不能为空") Instant occurredAt) {
    }

    /** 分样视图 */
    public record JointTestSplitView(
            String item,
            String sampleNo) {
    }

    /** 联检分样响应；version 为分样后的联检单版本 */
    public record JointTestSplitResponse(
            Long orderId,
            String requestId,
            Integer version,
            String status,
            List<JointTestSplitView> splits,
            Instant createdAt,
            boolean replayed) {
    }

    /** 联检结果/复测请求；浓度单位 mg/kg，有限非负 */
    public record JointTestResultRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "期望版本不能为空") @Positive(message = "期望版本必须为正") Integer expectedVersion,
            @NotBlank(message = "项目不能为空") String item,
            @NotNull(message = "浓度不能为空")
            @DecimalMin(value = "0", message = "浓度不能为负")
            @Digits(integer = 8, fraction = 4, message = "浓度超出可表示范围") BigDecimal concentration,
            @NotNull(message = "精确时刻不能为空") Instant occurredAt) {
    }

    /** 联检结果响应；supplementary=true 表示综合判定后只记补充信息 */
    public record JointTestResultResponse(
            Long resultId,
            String requestId,
            Long orderId,
            String item,
            BigDecimal concentration,
            BigDecimal limitValue,
            Boolean itemQualified,
            Integer itemVersion,
            Integer orderVersion,
            boolean supplementary,
            Instant createdAt,
            boolean replayed) {
    }

    /** 综合判定携带的项目结果版本 */
    public record JointTestJudgeItem(
            @NotBlank(message = "项目不能为空") String item,
            @NotNull(message = "结果版本不能为空") @Positive(message = "结果版本必须为正") Integer resultVersion) {
    }

    /** 综合判定请求；携带全部项目结果版本，版本失配零写入 */
    public record JointTestJudgeRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "期望版本不能为空") @Positive(message = "期望版本必须为正") Integer expectedVersion,
            @NotNull(message = "项目版本集合不能为空")
            @Size(min = 1, message = "项目版本集合不能为空") List<@Valid JointTestJudgeItem> items,
            @NotNull(message = "精确时刻不能为空") Instant occurredAt) {
    }

    /** 项目结果版本视图（冻结证据） */
    public record JointTestItemVersionView(
            String item,
            Integer resultVersion,
            BigDecimal concentration,
            BigDecimal limitValue,
            Boolean itemQualified) {
    }

    /** 综合判定响应；overall 为 PENDING 时未落判定（尚缺项目结果，只能待定） */
    public record JointTestJudgeResponse(
            Long judgmentId,
            String requestId,
            Long orderId,
            String overall,
            Integer version,
            Long batchId,
            String batchStatus,
            Instant retentionUntil,
            List<JointTestItemVersionView> itemVersions,
            Instant createdAt,
            boolean replayed) {
    }

    /** 联检结果视图 */
    public record JointTestResultView(
            Long resultId,
            String item,
            String sampleNo,
            BigDecimal concentration,
            Boolean itemQualified,
            Integer itemVersion,
            Boolean current,
            Boolean supplementary,
            Instant occurredAt,
            Instant createdAt) {
    }

    /** 联检单详情 */
    public record JointTestDetailResponse(
            Long orderId,
            Long batchId,
            String agencyCode,
            Integer version,
            String status,
            String overall,
            List<JointTestItemView> items,
            List<JointTestSplitView> splits,
            List<JointTestResultView> results,
            List<JointTestItemVersionView> judgmentItemVersions,
            Instant createdAt,
            Instant updatedAt) {
    }
}
