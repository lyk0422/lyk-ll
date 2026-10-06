package com.example.starter.transition.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 回抄浆掺配比例版本视图。
 */
public record BlendVersionView(
        String orderNo,
        Integer versionNo,
        BigDecimal targetRatioPct,
        String source,
        String requestId,
        LocalDateTime createdAt) {
}
