package com.example.starter.fermentation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * 降温指令请求：双乙酰连续两次合格后方可降温；指令与曲线版本切换在同一事务内完成。
 */
public record CoolingRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @NotNull Instant commandAt,
        @NotEmpty @Valid List<SegmentSpec> newCurve) {
}
