package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 撤人全员确认（幂等）。须在触发后 30 分钟内全员到位，恰满 30 分钟判定通过；
 * 确认通过与作业封锁在同一事务内完成。
 */
public record EvacConfirmRequest(
        @NotBlank String requestId,
        @NotBlank String chainNo,
        @NotNull LocalDateTime confirmTime) {
}
