package com.example.starter.rt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 受控治疗记录请求：须在排程窗口内、匹配排程/计划版本、遵守顺序分次规则。
 *
 * @param patientId        患者ID
 * @param planCode         计划业务编码
 * @param fractionNo       分次号（须为下一顺序分次）
 * @param deliveredDoseCgy 实际投递剂量(cGy)
 * @param requestId        幂等键
 */
public record TreatmentRequest(
        @NotBlank(message = "不能为空") String patientId,
        @NotBlank(message = "不能为空") String planCode,
        @NotNull(message = "不能为空") @Positive(message = "须为正数") Integer fractionNo,
        @NotNull(message = "不能为空") @Positive(message = "须为正数") Long deliveredDoseCgy,
        @NotBlank(message = "不能为空") String requestId) {
}
