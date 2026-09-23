package com.example.starter.specimen.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;

/**
 * 分装请求：requestId 为幂等键；expectedVersion 为样本乐观锁版本；
 * volumesUl 为本批各分装体积（微升，最多三位小数），任一项非法则整批回滚。
 */
public record AliquotRequest(
        @NotBlank String requestId,
        @PositiveOrZero long expectedVersion,
        @NotEmpty List<@NotNull @Positive BigDecimal> volumesUl) {
}
