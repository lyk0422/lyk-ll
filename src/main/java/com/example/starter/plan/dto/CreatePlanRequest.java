package com.example.starter.plan.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 创建放疗计划请求（合成患者数据）。
 *
 * @param patientNo           患者编号，业务唯一
 * @param patientName         患者姓名（合成）
 * @param prescriptionTotalGy 处方总剂量，单位Gy，须等于分次剂量×次数
 * @param fractionCount       分次次数
 * @param fractionDoseGy      单次分次剂量，单位Gy
 * @param oarDoses            危及器官剂量明细
 */
public record CreatePlanRequest(
        @NotBlank String patientNo,
        @NotBlank String patientName,
        @NotNull @DecimalMin("0.01") BigDecimal prescriptionTotalGy,
        @Min(1) int fractionCount,
        @NotNull @DecimalMin("0.01") BigDecimal fractionDoseGy,
        @NotEmpty List<@Valid OarDose> oarDoses) {
}
