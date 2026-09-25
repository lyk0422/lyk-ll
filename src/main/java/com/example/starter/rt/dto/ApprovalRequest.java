package com.example.starter.rt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 医师批准请求。
 *
 * @param patientId 患者ID
 * @param planCode  计划业务编码
 * @param versionNo 待批准版本号（须已通过物理审核）
 * @param requestId 批准幂等键
 */
public record ApprovalRequest(
        @NotBlank(message = "不能为空") String patientId,
        @NotBlank(message = "不能为空") String planCode,
        @NotNull(message = "不能为空") @Positive(message = "须为正数") Integer versionNo,
        @NotBlank(message = "不能为空") String requestId) {
}
