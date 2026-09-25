package com.example.starter.fermentation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * 入罐（满罐）请求：同一发酵罐同时刻只允许一个进行中批次。
 */
public record CreateBatchRequest(
        @NotBlank String batchNo,
        @NotBlank String tankCode,
        @NotNull Instant filledAt,
        @NotEmpty @Valid List<SegmentSpec> curve) {
}
