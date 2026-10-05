package com.example.starter.deicing.web.dto;

/**
 * 单种除冰液的库存台账视图（单位：升）。
 * 台账可复算：currentLiters = openingLiters + inboundLiters - consumedLiters - lentOutLiters。
 *
 * @param openingLiters       期初库存（合成数据一律经入库批次登记，期初为 0）
 * @param inboundLiters       累计入库量
 * @param consumedLiters      累计消耗量
 * @param lentOutLiters       当前未归还借出量
 * @param currentLiters       现存量 = 期初 + 入库 - 消耗 - 借出
 * @param activeReservedLiters 当前生效中的预占量（不影响现存量，影响可预占量）
 * @param reservableLiters    可预占量 = 未到期批次剩余 - 未归还借出 - 生效预占
 */
public record FluidLedgerView(
        String fluidCode,
        int openingLiters,
        int inboundLiters,
        int consumedLiters,
        int lentOutLiters,
        int currentLiters,
        int activeReservedLiters,
        int reservableLiters) {
}
