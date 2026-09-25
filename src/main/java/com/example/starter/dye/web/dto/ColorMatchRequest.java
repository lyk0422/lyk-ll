package com.example.starter.dye.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 打小样对色请求（CIE Lab）。ΔE 由服务端按 CIE76 复算，不信任客户端传入值。
 */
public record ColorMatchRequest(
        @NotBlank String requestId,
        @NotBlank String sampleCode,
        @NotNull BigDecimal stdL,
        @NotNull BigDecimal stdA,
        @NotNull BigDecimal stdB,
        @NotNull BigDecimal sampleL,
        @NotNull BigDecimal sampleA,
        @NotNull BigDecimal sampleB) {
}
