package com.example.starter.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * 归档后迟到测色数据：仅登记迟到事件，不改变终态判定。
 */
public record LateColorRequest(
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal deltaE,
        String note) {
}
