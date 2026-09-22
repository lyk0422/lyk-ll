package com.example.starter.arbitration.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 资源（关节/执行器）登记请求体，用于声明位置边界与限速、限加速度及安全动作。
 *
 * @param resourceName    资源名
 * @param minPosition     位置下限（弧度）
 * @param maxPosition     位置上限（弧度）
 * @param maxVelocity     最大速度绝对值（弧度/秒）
 * @param maxAcceleration 最大加速度绝对值（弧度/秒²）
 * @param safetyAction    失联/失权时的安全动作：HOLD/ZERO_VELOCITY/PRESET_POSITION
 * @param presetPosition  PRESET_POSITION 的预设位置（弧度），其余动作可空
 */
public record RegisterResourceRequest(
        @NotBlank String resourceName,
        double minPosition,
        double maxPosition,
        @NotNull Double maxVelocity,
        @NotNull Double maxAcceleration,
        @NotBlank String safetyAction,
        Double presetPosition) {
}
