package com.example.starter.deicing.web.dto;

import java.math.BigDecimal;

/**
 * 单种除冰液库存台账视图：期初 + 入库 - 消耗 - 借出 = 现存。
 *
 * @param reservedLiters  两步法 ACTIVE 预占量（升），不减少现存但占用可预占量
 * @param availableLiters 可预占量 = 未过期批次剩余合计 - ACTIVE 预占
 */
public record FluidLedgerView(
        String fluidCode,
        String fluidName,
        BigDecimal openingLiters,
        BigDecimal inboundLiters,
        BigDecimal consumedLiters,
        BigDecimal borrowedLiters,
        BigDecimal reservedLiters,
        BigDecimal currentLiters,
        BigDecimal availableLiters) {
}
