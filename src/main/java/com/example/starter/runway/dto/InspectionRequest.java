package com.example.starter.runway.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 巡检开始请求。低能见度（合成RVR阈值）期间须双人执行，单人记录不生效。
 *
 * @param startedAt      巡检开始时间（UTC）；不早于关闭后15分钟（恰满）即判定迟延并升级
 * @param inspectorCount 巡检人数，至少1人
 */
public record InspectionRequest(
        @NotNull Instant startedAt,
        @Min(1) int inspectorCount) {
}
