package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 跨罐调拨撤销：仅未执行（RESERVED）可撤销，释放双侧预占且仅一次；
 * 已执行不得撤回。
 *
 * @param transferId      调拨单ID
 * @param requestId       幂等键
 * @param expectedVersion 调拨单乐观锁期望版本
 * @param cancelledAt     撤销时刻（UTC）
 */
public record TankTransferCancelRequest(
        @NotNull Long transferId,
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotNull Instant cancelledAt) {
}
