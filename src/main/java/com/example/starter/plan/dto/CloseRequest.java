package com.example.starter.plan.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 疗程结案请求。结案为终态，重复结案返回幂等成功。
 *
 * @param requestId 幂等请求ID
 * @param operator  操作人（合成）
 */
public record CloseRequest(@NotBlank String requestId, @NotBlank String operator) {
}
