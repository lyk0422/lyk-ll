package com.example.starter.rt.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 物理审核请求。
 *
 * @param patientId 患者ID
 * @param planCode  计划业务编码
 * @param versionNo 计划版本号
 * @param requestId 审核幂等键
 */
public record AuditRequest(
        @NotBlank(message = "不能为空") String patientId,
        @NotBlank(message = "不能为空") String planCode,
        @jakarta.validation.constraints.NotNull(message = "不能为空")
        @jakarta.validation.constraints.Positive(message = "须为正数") Integer versionNo,
        @NotBlank(message = "不能为空") String requestId) {
}
