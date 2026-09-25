package com.example.starter.greenhouse.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 日评估查询/封存请求。day 为 UTC 日界（yyyy-MM-dd）。
 */
public record EvaluationRequest(
        @NotBlank(message = "不能为空") String zoneCode,
        @NotBlank(message = "不能为空") String day) {
}
