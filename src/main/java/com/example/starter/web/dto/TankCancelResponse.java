package com.example.starter.web.dto;

import com.example.starter.idempotent.Replayable;

import java.math.BigDecimal;

/**
 * 取消结果：释放尚未执行的预占。
 *
 * @param planId           计划ID
 * @param tankId           储罐ID
 * @param status           计划状态，成功为 CANCELLED
 * @param releasedQtyKg    本次释放的预占量 kg（重复取消为 0）
 * @param balanceKg        操作后余额 kg
 * @param reservedKg       操作后预占中数量 kg
 * @param version          操作后储罐版本
 * @param idempotentReplay 是否为同键同参重放
 */
public record TankCancelResponse(
        Long planId,
        Long tankId,
        String status,
        BigDecimal releasedQtyKg,
        BigDecimal balanceKg,
        BigDecimal reservedKg,
        long version,
        boolean idempotentReplay) implements Replayable<TankCancelResponse> {

    @Override
    public TankCancelResponse withReplay(boolean replay) {
        return new TankCancelResponse(planId, tankId, status, releasedQtyKg, balanceKg,
                reservedKg, version, replay);
    }
}
