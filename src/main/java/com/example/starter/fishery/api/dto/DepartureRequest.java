package com.example.starter.fishery.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 出港申报请求。
 *
 * @param requestId 幂等键
 * @param tripCode 航次业务编号（全库唯一）
 * @param vesselCode 渔船编号
 * @param departAt 出港时刻（UTC，ISO-8601）
 * @param lines 分鱼种申报捕捞量（千克）
 */
public record DepartureRequest(
        @NotBlank String requestId,
        @NotBlank String tripCode,
        @NotBlank String vesselCode,
        @NotNull Instant departAt,
        @NotEmpty @Valid List<SpeciesWeight> lines) {

    /** 鱼种数量行。 */
    public record SpeciesWeight(
            @NotBlank String speciesCode,
            @NotNull @DecimalMin(value = "0.001", message = "数量必须大于0") BigDecimal weightKg) {
    }
}
