package com.example.starter.rt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 分次执行记录请求。按“患者＋计划＋分次号”去重；分次号须连续，跳次拒绝并告警。
 *
 * @param patientId         患者ID
 * @param planCode          计划业务编码
 * @param fractionNo        分次号（从 1 连续）
 * @param deliveredDoseCgy  实际投递剂量(cGy)
 * @param requestId         分次记录幂等键
 */
public record FractionRequest(
        @NotBlank(message = "不能为空") String patientId,
        @NotBlank(message = "不能为空") String planCode,
        @NotNull(message = "不能为空") @Positive(message = "须为正数") Integer fractionNo,
        @NotNull(message = "不能为空") @Positive(message = "须为正数") Long deliveredDoseCgy,
        @NotBlank(message = "不能为空") String requestId) {
}
