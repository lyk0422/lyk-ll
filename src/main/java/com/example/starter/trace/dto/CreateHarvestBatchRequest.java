package com.example.starter.trace.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * 登记采收批请求。requestId 为幂等键；贡献比例两位小数且合计恰等于 100.00。
 */
public record CreateHarvestBatchRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @NotBlank String flowerPeriod,
        @NotEmpty List<@Valid ContributionItem> contributions) {

    /**
     * 单个蜂场贡献项。
     *
     * @param apiaryId 蜂场 ID
     * @param ratio    贡献比例，百分比，0 < ratio <= 100，两位小数
     */
    public record ContributionItem(
            @NotNull Long apiaryId,
            @NotNull
            @DecimalMin(value = "0.01", message = "贡献比例须大于 0")
            @DecimalMax(value = "100.00", message = "贡献比例不得超过 100")
            @Digits(integer = 3, fraction = 2, message = "贡献比例最多两位小数")
            BigDecimal ratio) {
    }
}
