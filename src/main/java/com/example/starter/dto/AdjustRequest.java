package com.example.starter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 调节令请求（初调节与再调节统一入口，按阀门开度步进执行）。
 *
 * <p>同一换热站同时刻只允许一个执行中任务：首个调节令创建任务，
 * 后续调节令在同一执行中任务内步进；并发下达时唯一受理。</p>
 *
 * @param requestId   幂等键，指纹覆盖本请求全部业务字段
 * @param stationCode 换热站编码
 * @param steps       步进列表，单步幅度不得超过 10%（恰 10% 合法）
 */
public record AdjustRequest(
        @NotBlank String requestId,
        @NotBlank String stationCode,
        @NotEmpty @Valid List<AdjustStep> steps
) {
}
