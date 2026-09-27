package com.example.starter.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 批量采集上传请求：一次至多 20 包；序号重复、未知点或超窗任一错误整批拒绝。
 *
 * @param expectedVersion 会话乐观版本（从 1 开始，成功变更加 1）
 */
public record AcquisitionBatchRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotNull @Size(min = 1, max = 20) List<@Valid Packet> packets) {

    /**
     * 采集包：会话内从 1 开始的连续序号、UTC 测量时刻、点号与有限正力矩(N·m)。
     */
    public record Packet(
            @Min(1) int seq,
            @NotBlank String pointCode,
            @NotNull Instant measuredAt,
            @NotNull @Positive BigDecimal torque) {
    }
}
