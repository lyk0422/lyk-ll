package com.example.starter.web.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;

/**
 * 资格续期请求。
 *
 * @param requestId 幂等请求 ID
 * @param applyDate 申请日期（UTC）；为空取当前 UTC 日期。须在到期前 30 日内，恰第 30 日受理
 */
public record RenewalRequest(
        @NotBlank String requestId,
        LocalDate applyDate) {

    /**
     * 续期结果。
     *
     * @param routeId     线路 ID
     * @param oldValidTo  续期前到期日
     * @param newValidTo  续期后到期日（顺延 1 年）
     */
    public record Result(Long routeId, LocalDate oldValidTo, LocalDate newValidTo) {
    }
}
