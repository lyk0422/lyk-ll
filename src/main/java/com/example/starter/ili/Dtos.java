package com.example.starter.ili;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 管道内检测对齐服务的 API 数据传输对象集合。
 */
public final class Dtos {

    private Dtos() {
    }

    /** 复核决策：确认维持原分级，降级则下调一级。 */
    public enum ReviewDecision {
        CONFIRM, DOWNGRADE
    }

    /** 焊缝登记项。 */
    public record WeldPayload(
            @NotBlank @Size(max = 64) String weldNo,
            @NotNull @DecimalMin("0") BigDecimal absMileageM) {
    }

    /** 创建管段请求，可同时登记焊缝台帐。 */
    public record CreateSegmentRequest(
            @NotBlank @Size(max = 64) String segmentCode,
            @NotBlank @Size(max = 128) String segmentName,
            List<WeldPayload> welds) {
    }

    /** 管段创建响应。 */
    public record SegmentResponse(long segmentId, String segmentCode, String segmentName, int weldCount) {
    }

    /** 单条缺陷读数。深度为壁厚百分比，里程为绝对里程（米）。 */
    public record DefectPayload(
            @NotBlank @Size(max = 64) String defectRef,
            @NotNull @DecimalMin("0") BigDecimal absMileageM,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal depthPct) {
    }

    /** 一个检测回合的缺陷清单。 */
    public record RunPayload(
            @NotBlank @Size(max = 64) String runCode,
            @NotNull LocalDate runDate,
            @NotEmpty List<DefectPayload> defects) {
    }

    /** 双回合缺陷清单导入与对齐请求，requestId 为幂等键。 */
    public record ImportAlignmentRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotBlank @Size(max = 64) String segmentCode,
            @NotNull @jakarta.validation.Valid RunPayload run1,
            @NotNull @jakarta.validation.Valid RunPayload run2) {
    }

    /** 匹配对视图。 */
    public record PairView(
            long pairId,
            String oldDefectRef,
            String newDefectRef,
            BigDecimal mileageDiffM,
            BigDecimal growthRatePctPerYear,
            String grade,
            String reviewStatus,
            String finalGrade,
            String reviewer) {
    }

    /** 未匹配缺陷视图。 */
    public record DefectView(String defectRef, BigDecimal absMileageM, BigDecimal depthPct) {
    }

    /** 对齐批次响应（导入、重放、查询共用）。 */
    public record AlignmentBatchResponse(
            long batchId,
            String requestId,
            String segmentCode,
            String run1Code,
            String run2Code,
            String status,
            List<PairView> pairs,
            List<DefectView> newFindings,
            List<DefectView> disappeared,
            Instant createdAt,
            Instant sealedAt) {
    }

    /** 复核提交请求。 */
    public record ReviewRequest(
            @NotNull Long pairId,
            @NotNull ReviewDecision decision,
            @NotBlank @Size(max = 64) String reviewer) {
    }

    /** 复核提交响应。 */
    public record ReviewResponse(long pairId, long batchId, String reviewStatus, String finalGrade) {
    }

    /** 封板响应。 */
    public record SealResponse(long batchId, String status, Instant sealedAt) {
    }
}
