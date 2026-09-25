package com.example.starter.plan.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 分次执行请求。
 *
 * @param requestId  幂等请求ID
 * @param fractionNo 分次号，须按顺序执行，跳次拒绝并告警
 * @param doseGy     实际执行分次剂量，单位Gy，须等于计划分次剂量
 */
public record FractionRequest(
        @NotBlank String requestId,
        @Min(1) int fractionNo,
        @NotNull @DecimalMin("0.01") BigDecimal doseGy) {
}
