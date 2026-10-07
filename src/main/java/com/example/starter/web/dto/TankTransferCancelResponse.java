package com.example.starter.web.dto;

import com.example.starter.idempotent.Replayable;

import java.math.BigDecimal;

/**
 * 跨罐调拨撤销结果：双侧预占已释放且仅释放一次。
 *
 * @param transferId       调拨单ID
 * @param status           CANCELLED 已撤销
 * @param releasedQtyKg    本次释放的双侧预占量 kg（重复撤销为 0，不重复返还）
 * @param transferVersion  撤销后调拨单版本
 * @param idempotentReplay 是否为同键同参重放
 */
public record TankTransferCancelResponse(
        Long transferId,
        String status,
        BigDecimal releasedQtyKg,
        long transferVersion,
        boolean idempotentReplay) implements Replayable<TankTransferCancelResponse> {

    @Override
    public TankTransferCancelResponse withReplay(boolean replay) {
        return new TankTransferCancelResponse(transferId, status, releasedQtyKg, transferVersion,
                replay);
    }
}
