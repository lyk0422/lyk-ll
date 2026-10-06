package com.example.starter.trace.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 追溯服务对外请求 / 响应报文定义。
 */
public final class TraceDtos {

    private TraceDtos() {
    }

    /** 蜂场贡献项：比例百分数，最多两位小数。 */
    public record ContributionItem(
            @NotNull Long apiaryId,
            @NotNull @DecimalMin("0.01") @DecimalMax("100.00") BigDecimal ratio) {
    }

    /** 登记采收批请求。 */
    public record RegisterHarvestBatchRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotBlank @Size(max = 64) String batchNo,
            @NotBlank @Size(max = 64) String floralPeriod,
            @NotEmpty List<@NotNull ContributionItem> contributions) {
    }

    /** 药残快检请求。 */
    public record RapidTestRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotBlank @Size(max = 64) String sampleNo,
            @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal inhibitionRate) {
    }

    /** 定量复检请求，result 仅允许 QUALIFIED / UNQUALIFIED。 */
    public record RetestRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotBlank @Size(max = 64) String sampleNo,
            @NotBlank String result) {
    }

    /** 创建加工批请求。 */
    public record ProcessingBatchRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotBlank @Size(max = 64) String processNo,
            @NotNull Long harvestBatchId) {
    }

    /** 蜂场贡献响应项。 */
    public record ContributionView(Long apiaryId, String apiaryCode, BigDecimal ratio) {
    }

    /** 采收批响应。 */
    public record HarvestBatchResponse(
            Long id,
            String batchNo,
            String floralPeriod,
            String status,
            List<ContributionView> contributions,
            LocalDateTime createdAt) {
    }

    /** 快检记录视图。 */
    public record RapidTestView(
            Long id,
            String sampleNo,
            BigDecimal inhibitionRate,
            String result,
            LocalDateTime createdAt) {
    }

    /** 复检记录视图。 */
    public record RetestView(
            Long id,
            String sampleNo,
            String result,
            LocalDateTime createdAt) {
    }

    /** 采收批详情：含全部快检与复检记录，被推翻的快检保留可查。 */
    public record HarvestBatchDetailResponse(
            Long id,
            String batchNo,
            String floralPeriod,
            String status,
            List<ContributionView> contributions,
            List<RapidTestView> rapidTests,
            List<RetestView> retests,
            LocalDateTime createdAt) {
    }

    /** 快检响应。 */
    public record RapidTestResponse(
            Long id,
            Long batchId,
            String sampleNo,
            BigDecimal inhibitionRate,
            String result,
            String batchStatus,
            LocalDateTime createdAt) {
    }

    /** 复检响应。 */
    public record RetestResponse(
            Long id,
            Long batchId,
            String sampleNo,
            String result,
            String batchStatus,
            LocalDateTime createdAt) {
    }

    /** 加工批响应。 */
    public record ProcessingBatchResponse(
            Long id,
            String processNo,
            Long harvestBatchId,
            LocalDateTime createdAt) {
    }

    /** 蜂场预警视图。 */
    public record AlertView(
            Long id,
            Long apiaryId,
            String floralPeriod,
            Integer suspectCount,
            LocalDateTime triggeredAt) {
    }

    /** 统一错误响应。 */
    public record ErrorResponse(String message) {
    }
}
