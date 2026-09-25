package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 排放读数批量上报请求。
 */
public record ReadingBatchRequest(
        @NotEmpty @Valid List<ReadingItem> readings) {
}
