package com.example.starter.web.dto;

import com.example.starter.idempotent.Replayable;

import java.math.BigDecimal;

/**
 * 跨罐调拨执行结果：双侧余额已同事务转移，双侧预占已释放，台账合计变化为 0。
 *
 * @param transferId       调拨单ID
 * @param status           EXECUTED 已执行
 * @param actualQtyKg      实际调拨量 kg（等于申请量）
 * @param credentialNo     合成凭据号
 * @param sourceBalanceKg  执行后源罐余额 kg
 * @param targetBalanceKg  执行后目标罐余额 kg
 * @param sourceVersion    执行后源罐版本
 * @param targetVersion    执行后目标罐版本
 * @param transferVersion  执行后调拨单版本
 * @param idempotentReplay 是否为同键同参重放
 */
public record TankTransferConfirmResponse(
        Long transferId,
        String status,
        BigDecimal actualQtyKg,
        String credentialNo,
        BigDecimal sourceBalanceKg,
        BigDecimal targetBalanceKg,
        long sourceVersion,
        long targetVersion,
        long transferVersion,
        boolean idempotentReplay) implements Replayable<TankTransferConfirmResponse> {

    @Override
    public TankTransferConfirmResponse withReplay(boolean replay) {
        return new TankTransferConfirmResponse(transferId, status, actualQtyKg, credentialNo,
                sourceBalanceKg, targetBalanceKg, sourceVersion, targetVersion, transferVersion,
                replay);
    }
}
