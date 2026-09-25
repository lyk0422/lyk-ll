package com.example.starter.deicing.web.dto;

import com.example.starter.deicing.domain.DeicingType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 除冰申请请求体。
 *
 * @param requestId       幂等键，由调用方保证唯一
 * @param flightNumber    航班号
 * @param standCode       机位编号
 * @param type            除冰类型：ONE_STEP 一步法 / TWO_STEP 两步法
 * @param requiredBays    所需除冰车位数
 * @param durationMinutes 除冰作业时长（分钟）
 * @param targetTakeoffTime 目标起飞时刻（UTC）
 */
public record ApplicationRequest(
        @NotBlank String requestId,
        @NotBlank String flightNumber,
        @NotBlank String standCode,
        @NotNull DeicingType type,
        @Min(1) int requiredBays,
        @Min(1) int durationMinutes,
        @NotNull Instant targetTakeoffTime) {
}
