package com.example.starter.dye.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 出缸测色上报请求（不带 requestId 幂等键：归档后迟到的测色数据只记迟到事件）。
 */
public record DischargeMeasureRequest(
        @NotBlank String runNo,
        @NotNull BigDecimal deltaE) {
}
