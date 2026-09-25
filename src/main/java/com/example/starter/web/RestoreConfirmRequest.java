package com.example.starter.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 复电确认请求：调度（DISPATCHER）与现场（FIELD）各确认一次，单方不生效；
 * 第二次确认齐备时，负荷恢复记录与复电确认在同一事务写入。
 *
 * @param restoredLoadKw 恢复负荷，单位：千瓦（完成双确认的那次请求必传）
 * @param recoveredUsers 恢复用户数，单位：户（完成双确认的那次请求必传）
 */
public record RestoreConfirmRequest(
        @NotBlank String requestId,
        @NotBlank String actor,
        @NotBlank String actorName,
        @NotNull BigDecimal restoredLoadKw,
        @NotNull Integer recoveredUsers) {
}
