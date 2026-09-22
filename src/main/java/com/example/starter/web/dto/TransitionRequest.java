package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 显式状态迁移请求。
 *
 * @param targetStatus    目标状态（TRACKING/OCCLUDED/LOST/REIDENTIFIED/RECYCLED）
 * @param expectedVersion 期望版本号，用于原子 CAS
 * @param eventTime       事件时间，epoch 毫秒（UTC）
 * @param reason          迁移原因说明
 */
public record TransitionRequest(
        @NotBlank String targetStatus,
        @NotNull Long expectedVersion,
        @NotNull Long eventTime,
        String reason) {
}
