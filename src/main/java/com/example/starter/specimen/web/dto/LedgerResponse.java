package com.example.starter.specimen.web.dto;

import java.math.BigDecimal;

/**
 * 体积账本响应：各状态分装体积汇总（微升，三位小数）；
 * conserved 为 true 表示 剩余量 + 各状态分装量之和 == 入库总量，精确守恒。
 */
public record LedgerResponse(
        String specimenId,
        BigDecimal totalVolumeUl,
        BigDecimal remainingVolumeUl,
        BigDecimal availableVolumeUl,
        BigDecimal reservedVolumeUl,
        BigDecimal consumedVolumeUl,
        BigDecimal quarantinedVolumeUl,
        long aliquotCount,
        boolean conserved) {
}
