package com.example.starter.atm.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 编制加钞计划请求。plannedAmount 为空时按历史日均取现预测；单位：分。
 */
public record PreparePlanRequest(
        @NotBlank String requestId,
        @NotBlank String atmNo,
        @NotBlank String boxNo,
        @Positive Long plannedAmount
) {
}
