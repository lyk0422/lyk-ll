package com.example.starter.specimen.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * 检测申请请求：requestId 为幂等键；expectedVersion 为分装乐观锁版本；
 * reservedVolumeUl 为占用体积（微升，最多三位小数），不得超过分装体积。
 */
public record AssayCreateRequest(
        @NotBlank String requestId,
        @NotBlank String aliquotId,
        @PositiveOrZero long expectedVersion,
        @NotNull @Positive BigDecimal reservedVolumeUl,
        @NotBlank String project,
        @NotBlank String custodian) {
}
