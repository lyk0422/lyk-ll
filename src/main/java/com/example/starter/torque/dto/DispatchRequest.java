package com.example.starter.torque.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

/**
 * 派工请求：创建工序卡及其工艺卡紧固件定义。
 */
public record DispatchRequest(
        @NotBlank String requestId,
        @NotBlank String cardNo,
        @NotBlank String station,
        @NotBlank String productNo,
        @NotEmpty @Valid List<FastenerSpecDto> fasteners
) {
    public record FastenerSpecDto(
            @NotBlank String pointCode,
            @NotNull @Positive BigDecimal nominalTorque,
            boolean hPoint
    ) {
    }
}
