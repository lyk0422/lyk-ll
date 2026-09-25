package com.example.starter.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 日常温湿度批量上报（非幂等业务数据；归档后上报只登记迟到事件）。
 */
public record ReadingBatchRequest(
        @NotEmpty @Valid List<@Valid ReadingPoint> readings
) {
}
