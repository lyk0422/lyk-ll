package com.example.starter.dto;

import com.example.starter.domain.TestPhase;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 制动试验请求。
 *
 * @param requestId         幂等键
 * @param phase             试验项: SENSITIVITY=感度, STABILITY=安定
 * @param reductionKpa      减压量(kPa): 感度恰50合格, 安定恰140合格
 * @param allCarsBraked     感度试验必填: 全列是否制动
 * @param emergencyOccurred 安定试验必填: 是否发生紧急制动
 */
public record BrakeTestRequest(
        @NotBlank String requestId,
        @NotNull TestPhase phase,
        @Min(0) int reductionKpa,
        Boolean allCarsBraked,
        Boolean emergencyOccurred) {
}
