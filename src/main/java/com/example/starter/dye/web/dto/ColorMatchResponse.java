package com.example.starter.dye.web.dto;

import java.math.BigDecimal;

/**
 * 对色结果。放行阈值 ΔE &lt;= 0.8，恰 0.8 合格。
 */
public record ColorMatchResponse(
        String requestId,
        String sampleCode,
        BigDecimal deltaE,
        boolean passed) {
}
