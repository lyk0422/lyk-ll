package com.example.starter.planningscene.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 单个关节赋值；关节名必须存在于机器人模型，位置必须有限且落在关节范围内。
 */
public record JointValueDto(
        @NotBlank String name,
        @NotNull Double position) {
}
