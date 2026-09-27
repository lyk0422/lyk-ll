package com.example.starter.web.dto;

import com.example.starter.idempotent.Replayable;

import java.math.BigDecimal;
import java.util.List;

/**
 * 补液交接确认结果。
 *
 * @param refillOrderId    补液单ID
 * @param batchNo          运输批号
 * @param creditedKg       入账数量 kg
 * @param balanceKg        入账后余额 kg
 * @param reservedKg       预占合计 kg
 * @param closedEventIds   本次入账关闭的待补液事件ID集合
 * @param version          变动后储罐版本
 * @param idempotentReplay 是否为同键同参重放
 */
public record RefillConfirmResponse(
        Long refillOrderId,
        String batchNo,
        BigDecimal creditedKg,
        BigDecimal balanceKg,
        BigDecimal reservedKg,
        List<Long> closedEventIds,
        int version,
        boolean idempotentReplay) implements Replayable<RefillConfirmResponse> {

    @Override
    public RefillConfirmResponse withReplay(boolean replay) {
        return new RefillConfirmResponse(refillOrderId, batchNo, creditedKg, balanceKg,
                reservedKg, closedEventIds, version, replay);
    }
}
