package com.example.starter.ili.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 单条缺陷读数输入（合成数据）。
 *
 * @param defectRef    检测方缺陷编号，同批次同回合内唯一
 * @param absPositionM 缺陷绝对里程，单位米
 * @param depthPct     缺陷深度，占壁厚百分比，取值 0~100
 */
public record DefectInput(
        @NotBlank @Size(max = 64) String defectRef,
        @NotNull @DecimalMin("0.0") @DecimalMax("500000.0") Double absPositionM,
        @NotNull @DecimalMin("0.0") @DecimalMax("100.0") Double depthPct) {
}
