package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 对外接口请求报文。所有时刻字段均为 UTC（ISO-8601），数量单位为千克（kg）。
 */
public final class ApiRequests {

    private ApiRequests() {
    }

    /** 鱼种申报/核销数量。 */
    public record SpeciesQuantity(
            @NotBlank(message = "speciesCode 不能为空") String speciesCode,
            @NotNull(message = "quantityKg 不能为空") @Positive(message = "quantityKg 必须为正数") BigDecimal quantityKg) {
    }

    /** 出港申报请求。 */
    public record DepartureRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "vesselId 不能为空") Long vesselId,
            @NotNull(message = "departureTime 不能为空") Instant departureTime,
            @NotBlank(message = "fishingArea 不能为空") String fishingArea,
            @NotEmpty(message = "items 不能为空") List<@Valid SpeciesQuantity> items) {
    }

    /** 渔捞日志明细项，operationTime 决定日界归属。 */
    public record LogItem(
            @NotBlank(message = "speciesCode 不能为空") String speciesCode,
            @NotNull(message = "quantityKg 不能为空") @Positive(message = "quantityKg 必须为正数") BigDecimal quantityKg,
            @NotNull(message = "operationTime 不能为空") Instant operationTime) {
    }

    /** 渔捞日志提交请求。 */
    public record LogbookRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "fishingArea 不能为空") String fishingArea,
            @NotEmpty(message = "items 不能为空") List<@Valid LogItem> items) {
    }

    /** 进港卸渔明细项。 */
    public record ArrivalItem(
            @NotBlank(message = "speciesCode 不能为空") String speciesCode,
            @NotNull(message = "unloadedKg 不能为空") @Positive(message = "unloadedKg 必须为正数") BigDecimal unloadedKg) {
    }

    /** 进港报告请求；卸渔偏差超过 3% 时 differenceNote 必填。 */
    public record ArrivalRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "arrivalTime 不能为空") Instant arrivalTime,
            @NotEmpty(message = "items 不能为空") List<@Valid ArrivalItem> items,
            @Size(max = 512, message = "differenceNote 最长 512 字符") String differenceNote) {
    }

    /** 配额核销请求。 */
    public record SettlementRequest(
            @NotBlank(message = "requestId 不能为空") String requestId) {
    }

    /** 航次结案请求。 */
    public record ClosureRequest(
            @NotBlank(message = "requestId 不能为空") String requestId) {
    }

    /** 复核结论请求；finalItems 非空时以其为最终口径并在同一事务内扣减配额。 */
    public record ReviewResolutionRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "conclusion 不能为空") @Size(max = 512, message = "conclusion 最长 512 字符") String conclusion,
            List<@Valid SpeciesQuantity> finalItems) {
    }
}
