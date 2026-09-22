package com.example.starter.arbitration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 运动指令：携带时戳、序列号与目标位置/速度/加速度。
 *
 * @param controllerId 提交控制器ID，必须为资源当前持有者
 * @param resourceId   资源标识
 * @param seq          序列号，按资源+控制器严格递增
 * @param ts           指令时戳（epoch毫秒），必须单调且不允许时钟回拨
 * @param position     目标位置，必须在资源位置边界内
 * @param velocity     目标速度，绝对值不得超过限速
 * @param acceleration 目标加速度，绝对值不得超过限加速度
 */
public record CommandRequest(
        @NotBlank String controllerId,
        @NotBlank String resourceId,
        @NotNull Long seq,
        @NotNull Long ts,
        @NotNull Double position,
        @NotNull Double velocity,
        @NotNull Double acceleration) {
}
