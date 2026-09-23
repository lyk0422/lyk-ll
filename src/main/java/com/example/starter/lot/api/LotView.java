package com.example.starter.lot.api;

import com.example.starter.lot.model.LotStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 批次视图：主信息 + 当前生效隔离来源。
 */
public record LotView(
        String lotId,
        String unit,
        BigDecimal initialQty,
        BigDecimal availableQty,
        BigDecimal consumedQty,
        LotStatus status,
        long version,
        List<String> activeQuarantineSources,
        LocalDateTime createdAt
) {
}
