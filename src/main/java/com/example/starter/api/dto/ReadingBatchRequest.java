package com.example.starter.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 排放读数批量上报（去重主键：测点＋采样时刻）。
 */
public record ReadingBatchRequest(
        @NotBlank String planNo,
        @NotEmpty @Valid List<ReadingItem> readings) {
}
