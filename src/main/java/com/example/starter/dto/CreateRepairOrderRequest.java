package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 维修单创建请求：一张维修单对应一条缺陷记录。
 */
public record CreateRepairOrderRequest(
        @NotBlank String requestId,
        @NotNull Long defectId) {
}
