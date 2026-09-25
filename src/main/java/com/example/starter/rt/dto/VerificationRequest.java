package com.example.starter.rt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 首次治疗前模体验证请求。
 *
 * @param patientId       患者ID
 * @param planCode        计划业务编码
 * @param versionNo       计划版本号（须医师已批准）
 * @param plannedDoseCgy  模体计划参考剂量(cGy)
 * @param measuredDoseCgy 模体实测剂量(cGy)；偏差 = |实测-计划|/计划×100%，恰 3% 合法
 * @param requestId       验证幂等键
 */
public record VerificationRequest(
        @NotBlank(message = "不能为空") String patientId,
        @NotBlank(message = "不能为空") String planCode,
        @NotNull(message = "不能为空") @Positive(message = "须为正数") Integer versionNo,
        @NotNull(message = "不能为空") @Positive(message = "须为正数") Long plannedDoseCgy,
        @NotNull(message = "不能为空") @Positive(message = "须为正数") Long measuredDoseCgy,
        @NotBlank(message = "不能为空") String requestId) {
}
