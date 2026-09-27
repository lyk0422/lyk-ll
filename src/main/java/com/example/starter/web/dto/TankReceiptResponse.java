package com.example.starter.web.dto;

import com.example.starter.idempotent.Replayable;

import java.math.BigDecimal;

/**
 * 执行回执结果：预占转实际消耗，未用部分释放。
 *
 * @param planId           计划ID
 * @param tankId           储罐ID
 * @param status           计划状态，成功为 EXECUTED
 * @param actualQtyKg      实际消耗量 kg
 * @param releasedQtyKg    释放的未用预占量 kg
 * @param balanceKg        操作后余额 kg
 * @param reservedKg       操作后预占中数量 kg
 * @param version          操作后储罐版本
 * @param idempotentReplay 是否为同键同参重放
 */
public record TankReceiptResponse(
        Long planId,
        Long tankId,
        String status,
        BigDecimal actualQtyKg,
        BigDecimal releasedQtyKg,
        BigDecimal balanceKg,
        BigDecimal reservedKg,
        long version,
        boolean idempotentReplay) implements Replayable<TankReceiptResponse> {

    @Override
    public TankReceiptResponse withReplay(boolean replay) {
        return new TankReceiptResponse(planId, tankId, status, actualQtyKg, releasedQtyKg,
                balanceKg, reservedKg, version, replay);
    }
}
