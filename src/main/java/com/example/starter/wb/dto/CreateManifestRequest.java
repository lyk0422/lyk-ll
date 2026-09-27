package com.example.starter.wb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;

/**
 * 预配舱单创建请求（首版）。
 *
 * @param assemblyControlled 是否启用集装器装配控制；缺省 false，未启用的既有舱单行为不变
 */
public record CreateManifestRequest(
        @NotBlank String requestId,
        @NotNull Long flightId,
        @NotNull @PositiveOrZero BigDecimal fuelKg,
        @NotBlank String createdBy,
        @NotEmpty List<LoadItem> items,
        Boolean assemblyControlled
) {
    /** 兼容未启用装配控制的既有调用：缺省不启用。 */
    public CreateManifestRequest(String requestId, Long flightId, BigDecimal fuelKg,
                                 String createdBy, List<LoadItem> items) {
        this(requestId, flightId, fuelKg, createdBy, items, null);
    }
}
