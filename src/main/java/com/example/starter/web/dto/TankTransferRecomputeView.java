package com.example.starter.web.dto;

import java.math.BigDecimal;

/**
 * 调拨复算视图：从调拨单与双侧台账分录重新计算双侧预占、余额及调拨流量，
 * 不读取任何缓存结论。
 *
 * @param transferId                     调拨单ID
 * @param status                         调拨单状态
 * @param quantityKg                     申请调拨量 kg
 * @param transferredKg                  已执行调拨流量 kg（未执行为 0）
 * @param sourceTankId                   源罐ID
 * @param targetTankId                   目标罐ID
 * @param sourceReservedDeltaKg          本单调拨在源罐的预占变动合计 kg（台账复算）
 * @param targetIncomingReservedDeltaKg  本单调拨在目标罐的接收预占变动合计 kg（台账复算）
 * @param netBalanceDeltaKg              本单双侧余额变动合计 kg（执行后应为 0，守恒）
 * @param sourceBalanceKg                源罐余额 kg（全部台账余额变动复算）
 * @param targetBalanceKg                目标罐余额 kg（全部台账余额变动复算）
 * @param sourceReservedKg               源罐预占中数量 kg（全部台账预占变动复算）
 * @param targetIncomingReservedKg       目标罐接收预占 kg（全部台账接收预占变动复算）
 */
public record TankTransferRecomputeView(
        Long transferId,
        String status,
        BigDecimal quantityKg,
        BigDecimal transferredKg,
        Long sourceTankId,
        Long targetTankId,
        BigDecimal sourceReservedDeltaKg,
        BigDecimal targetIncomingReservedDeltaKg,
        BigDecimal netBalanceDeltaKg,
        BigDecimal sourceBalanceKg,
        BigDecimal targetBalanceKg,
        BigDecimal sourceReservedKg,
        BigDecimal targetIncomingReservedKg) {
}
