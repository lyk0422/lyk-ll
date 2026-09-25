package com.example.starter.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * 派工请求：创建工序卡及其紧固点工艺定义。
 *
 * @param fasteners 紧固点列表，力矩标称值单位 N·m；允差缺省 ±5%
 */
public record DispatchRequest(
        @NotBlank String requestId,
        @NotBlank String cardNo,
        @NotBlank String station,
        @NotBlank String productSn,
        @NotNull @Pattern(regexp = "[HN]") String witnessPoint,
        @NotBlank String operator,
        @NotNull List<FastenerSpec> fasteners) {

    /**
     * 紧固点工艺规格。
     */
    public record FastenerSpec(
            @NotBlank String pointCode,
            @NotNull @Positive BigDecimal nominalTorque,
            @Positive BigDecimal tolerancePct) {
    }
}
