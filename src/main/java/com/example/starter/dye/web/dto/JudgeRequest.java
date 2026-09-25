package com.example.starter.dye.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 缸差判定请求：同配方对照基准缸的出缸 ΔE 与本缸出缸 ΔE 差值超过 0.5 须回修。
 * 基准缸可省略（baselineRunNo 为空表示以标准色为基准，直接按本缸出缸 ΔE 判定合格）。
 */
public record JudgeRequest(
        @NotBlank String requestId,
        @NotBlank String runNo,
        String baselineRunNo) {
}
