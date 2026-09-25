package com.example.starter.plan.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 危及器官剂量项。
 *
 * @param organ  危及器官名称，须存在于约束模板
 * @param doseGy 计划剂量，单位Gy
 */
public record OarDose(
        @NotBlank String organ,
        @NotNull @DecimalMin("0.0") BigDecimal doseGy) {
}
