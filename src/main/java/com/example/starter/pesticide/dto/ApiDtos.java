package com.example.starter.pesticide.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

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
}
