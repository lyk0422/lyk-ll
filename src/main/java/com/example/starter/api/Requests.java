package com.example.starter.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.example.starter.domain.WorkType;

/**
 * 写操作请求 DTO。所有写操作携带 requestId 作为幂等键，
 * 指纹覆盖 requestId 与全部业务字段。
 */
public final class Requests {

    private Requests() {
    }

    /** 路段建档请求。 */
    public record SegmentRegisterRequest(
            @NotBlank String requestId,
            @NotBlank String code,
            @NotBlank String name) {
    }

    /** 树木建档请求，评估因子用于合成评估模型。 */
    public record TreeRegisterRequest(
            @NotBlank String requestId,
            @NotBlank String segmentCode,
            @NotBlank String treeCode,
            @DecimalMin("0") double tiltDegrees,
            @Min(0) @Max(3) int diseaseLevel,
            @Min(0) int ageYears,
            @Min(0) @Max(3) int windExposure) {
    }

    /** 作业许可申请请求。 */
    public record PermitApplyRequest(
            @NotBlank String requestId,
            @NotBlank String segmentCode,
            @NotNull Long planId,
            @NotNull WorkType workType) {
    }

    /** 作业回传请求，前后照片标识与弃枝清运记录必填。 */
    public record ReportSubmitRequest(
            @NotBlank String requestId,
            @NotBlank String permitNo,
            @NotBlank String beforePhoto,
            @NotBlank String afterPhoto,
            @NotBlank String disposalRecord) {
    }

    /** 大风预警发布请求，level 为合成预警级别1-4。 */
    public record WarningPublishRequest(
            @NotBlank String requestId,
            @NotBlank String warningNo,
            @Min(1) @Max(4) int level) {
    }

    /** 大风预警解除请求。 */
    public record WarningCancelRequest(
            @NotBlank String requestId,
            @NotBlank String warningNo) {
    }

    /** 年度风险复评请求，携带最新评估因子。 */
    public record ReevaluateRequest(
            @NotBlank String requestId,
            @NotBlank String treeCode,
            @DecimalMin("0") double tiltDegrees,
            @Min(0) @Max(3) int diseaseLevel,
            @Min(0) int ageYears,
            @Min(0) @Max(3) int windExposure) {
    }
}
