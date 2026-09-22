package com.example.starter.arbitration.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 控制器下发的运动指令请求体。
 *
 * @param controllerId       发送控制器（须持有目标资源有效租约）
 * @param requestId          控制器侧幂等键
 * @param resourceName       目标资源名
 * @param timestampMs        指令时戳（UTC epoch 毫秒）
 * @param sequence           控制器内单调递增序列号
 * @param targetPosition     目标位置（弧度）
 * @param targetVelocity     目标速度（弧度/秒）
 * @param targetAcceleration 目标加速度（弧度/秒²）
 */
public record CommandRequest(
        @NotBlank String controllerId,
        @NotBlank String requestId,
        @NotBlank String resourceName,
        @NotNull Long timestampMs,
        long sequence,
        double targetPosition,
        double targetVelocity,
        double targetAcceleration) {
}
