package com.example.starter.wbb.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 值机数据上载请求：以全量明细替换当前业载（仅 PREPARED/CHECK_IN 状态可用）。
 */
public record CheckinRequest(
        @NotBlank String requestId,
        @Valid List<PayloadItemDto> items
) {
}
