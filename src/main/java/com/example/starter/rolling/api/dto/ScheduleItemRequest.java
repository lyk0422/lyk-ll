package com.example.starter.rolling.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 排产块请求：一块待轧钢板对应的轧制作业。
 *
 * @param contractNo 轧制合同号
 * @param widthMm    板宽（毫米），须存在于合成宽度档位表
 * @param rollingKm  计划轧制里程（公里）
 * @param warmup     是否烫辊材（换辊后前 5 块排入）
 * @param startTime  作业开始时刻（含）
 * @param endTime    作业结束时刻（不含），与开始时刻构成 [start, end)
 */
public record ScheduleItemRequest(
        @NotBlank String contractNo,
        @NotNull Integer widthMm,
        @NotNull @Positive BigDecimal rollingKm,
        boolean warmup,
        @NotNull LocalDateTime startTime,
        @NotNull LocalDateTime endTime) {
}
