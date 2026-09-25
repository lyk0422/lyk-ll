package com.example.starter.wbb.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 最后一分钟修正（LMC）请求：deltaItems 为相对当前舱单的增量行，pieceCount/weightKg 可为负。
 * 累计增量在 ±5 人且 ±500kg（含边界）内原地修正；任一超限则重打并生成新版本。
 */
public record LmcRequest(
        @NotBlank String requestId,
        @Valid List<PayloadItemDto> deltaItems
) {
}
