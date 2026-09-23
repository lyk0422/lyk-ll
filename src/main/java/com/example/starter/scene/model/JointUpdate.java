package com.example.starter.scene.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 单个关节位置更新项。
 *
 * @param jointName 关节名称，必须存在于机器人模型
 * @param position  目标位置（弧度），必须为有限数值且在关节上下限内
 */
public record JointUpdate(
        @NotBlank String jointName,
        @NotNull Double position) {
}
