package com.example.starter.rt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 新建合成患者请求。
 *
 * @param patientId   患者ID（P 开头，调用方提供合成编号）
 * @param patientName 患者姓名（合成）
 */
public record CreatePatientRequest(
        @NotBlank(message = "不能为空") String patientId,
        @NotBlank(message = "不能为空") String patientName) {
}
