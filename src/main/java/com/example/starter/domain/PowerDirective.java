package com.example.starter.domain;

import java.time.LocalDateTime;

/**
 * 限功率指令。
 *
 * @param id                   主键
 * @param transmitterId        目标发射机主键
 * @param status               状态：EXECUTING/LIFTED
 * @param currentLimitW        当前限值，单位 W
 * @param createdAt            创建时刻（UTC）
 * @param liftedAt             解除时刻（UTC）；null=执行中
 * @param liftOpsConfirmer     解除确认人（运行岗）
 * @param liftMonitorConfirmer 解除确认人（监测岗）
 */
public record PowerDirective(long id, long transmitterId, String status, int currentLimitW,
                             LocalDateTime createdAt, LocalDateTime liftedAt,
                             String liftOpsConfirmer, String liftMonitorConfirmer) {

    public static final String STATUS_EXECUTING = "EXECUTING";
    public static final String STATUS_LIFTED = "LIFTED";
}
