package com.example.starter.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 批量读数上报；按“测区＋时刻”去重。
 */
public record ReadingBatchRequest(
        @NotEmpty @Valid List<ReadingItem> readings) {
}
