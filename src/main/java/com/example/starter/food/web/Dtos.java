package com.example.starter.food.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 食品合规模块 HTTP 接口的请求/响应数据结构。
 */
public final class Dtos {

    private Dtos() {
    }

    // ---------------- 请求 ----------------

    /** 配方提交请求；requestId 为幂等键，指纹覆盖 requestId 与全部业务字段。 */
    public record SubmitRecipeRequest(
            @NotBlank String requestId,
            @NotBlank String productCode,
            @NotBlank String recipeVersion,
            @NotBlank String categoryCode,
            @Valid @NotNull List<AdditiveInput> additives,
            @Valid List<IngredientInput> ingredients) {
    }

    /** 直接加入的添加剂，amount 单位 g/kg（以终产品质量计）。 */
    public record AdditiveInput(
            @NotBlank String additiveCode,
            @NotNull @PositiveOrZero BigDecimal amount) {
    }

    /** 复合配料，proportion 为占终产品质量比例（0~1）。 */
    public record IngredientInput(
            @NotBlank String compoundCode,
            @NotNull @PositiveOrZero BigDecimal proportion) {
    }

    /** 仅含幂等键的动作请求（批准、注销等）。 */
    public record ActionRequest(@NotBlank String requestId) {
    }

    /** 合规审查请求；standardVersion 缺省取最新已发布标准。 */
    public record ReviewRecipeRequest(@NotBlank String requestId, String standardVersion) {
    }

    /** 按已批准配方版本发起量产备案。 */
    public record FileFilingRequest(@NotBlank String requestId, @NotNull Long recipeId) {
    }

    /** 备案复审（标准更新后）。 */
    public record ReviewFilingRequest(@NotBlank String requestId) {
    }

    /** 量产记录上报。 */
    public record ProductionReportRequest(
            @NotBlank String requestId,
            @NotBlank String batchNo,
            @NotNull @PositiveOrZero BigDecimal quantityKg) {
    }

    /** 发布新标准版本（限量行已预先维护，发布时刻决定旧备案进入复审观察）。 */
    public record PublishStandardRequest(@NotBlank String requestId, @NotBlank String version) {
    }

    // ---------------- 响应 ----------------

    /** 逐项判定明细中的单条记录。 */
    public record ComplianceItemResponse(
            String itemType,
            String additiveCode,
            String additiveName,
            String functionType,
            String source,
            BigDecimal directAmount,
            BigDecimal carryOverAmount,
            BigDecimal effectiveAmount,
            BigDecimal maxAmount,
            BigDecimal ratio,
            boolean pass,
            String message) {
    }

    /** 配方及其最近一次合规审查结果。 */
    public record RecipeResponse(
            Long id,
            String requestId,
            String productCode,
            String recipeVersion,
            String categoryCode,
            String status,
            String reviewedStandardVersion,
            Boolean compliant,
            Instant createdAt,
            Instant reviewedAt,
            Instant approvedAt,
            List<ComplianceItemResponse> reviewItems) {
    }

    /** 量产备案视图。 */
    public record FilingResponse(
            Long id,
            String requestId,
            String productCode,
            Long recipeId,
            String recipeVersion,
            String standardVersion,
            String status,
            Instant filedAt,
            Instant reviewDueAt,
            Instant reviewedAt,
            String reviewedStandardVersion,
            Instant suspendedAt,
            String suspendReason,
            Instant cancelledAt) {
    }

    /** 量产记录上报结果。 */
    public record ProductionReportResponse(
            Long id,
            Long filingId,
            String batchNo,
            BigDecimal quantityKg,
            Instant reportedAt) {
    }

    /** 标准发布结果。 */
    public record PublishStandardResponse(
            String version,
            Instant publishedAt,
            Instant reviewDueAt,
            int affectedFilings) {
    }

    /** 逾期暂停扫描结果。 */
    public record SweepResponse(Instant now, int suspendedCount) {
    }

    /** 简单动作结果。 */
    public record MessageResponse(String message) {
    }
}
