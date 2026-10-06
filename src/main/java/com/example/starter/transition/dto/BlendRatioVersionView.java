package com.example.starter.transition.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 掺配比例版本视图。
 */
public record BlendRatioVersionView(
        String orderNo,
        Integer versionNo,
        BigDecimal targetRatioPct,
        BigDecimal ratioDeltaPct,
        String adjustMode,
        String requestId,
        LocalDateTime decidedAt) {
}
