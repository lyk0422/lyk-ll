package com.example.starter.web.dto;

import com.example.starter.idempotent.Replayable;

import java.math.BigDecimal;
import java.util.List;

/**
 * 补液入账结果：原子入账并关闭可满足的待补液事件（不自动重发旧加注命令）。
 *
 * @param refillId         补液单ID
 * @param tankId           储罐ID
 * @param transportBatchNo 运输批号
 * @param quantityKg       入账数量 kg
 * @param balanceKg        入账后余额 kg
 * @param reservedKg       入账后预占中数量 kg
 * @param availableKg      入账后可用量 kg
 * @param closedEventIds   本次入账关闭的待补液事件ID集合
 * @param version          入账后储罐版本
 * @param idempotentReplay 是否为同键同参重放
 */
public record TankRefillResponse(
        Long refillId,
        Long tankId,
        String transportBatchNo,
        BigDecimal quantityKg,
        BigDecimal balanceKg,
        BigDecimal reservedKg,
        BigDecimal availableKg,
        List<Long> closedEventIds,
        long version,
        boolean idempotentReplay) implements Replayable<TankRefillResponse> {

    @Override
    public TankRefillResponse withReplay(boolean replay) {
        return new TankRefillResponse(refillId, tankId, transportBatchNo, quantityKg, balanceKg,
                reservedKg, availableKg, closedEventIds, version, replay);
    }
}
