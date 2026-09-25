package com.example.starter.pump.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 气象预警上送请求。
 *
 * @param requestId  幂等键
 * @param zoneCode   预警影响的流域分区代码
 * @param alertLevel 预警级别代码 BLUE/YELLOW/ORANGE/RED（合成级别表）
 * @param alertTime  预警发布时刻（Asia/Shanghai）
 */
public record WarningRequest(
        @NotBlank String requestId,
        @NotBlank String zoneCode,
        @NotBlank String alertLevel,
        @NotNull LocalDateTime alertTime
) {
}
