package com.example.starter.web.dto;

import com.example.starter.idempotent.Replayable;

import java.math.BigDecimal;
import java.util.List;

/**
 * 补液入账结果：原子入账并关闭可满足的待补液事件，不自动重发旧加注命令。
 *
 * @param refillId         补液单ID
 * @param transportBatchNo 唯一运输批号
 * @param quantityKg       入账数量 kg
 * @param balanceAfterKg   入账后余额 kg
 * @param closedEventIds   本次入账关闭的待补液事件ID列表（可为空）
 * @param version          入账后储罐版本号
 * @param idempotentReplay 是否为同键同参重放
 */
public record RefillResponse(
        Long refillId,
        String transportBatchNo,
        BigDecimal quantityKg,
        BigDecimal balanceAfterKg,
        List<Long> closedEventIds,
        long version,
        boolean idempotentReplay) implements Replayable<RefillResponse> {

    @Override
    public RefillResponse withReplay(boolean replay) {
        return new RefillResponse(refillId, transportBatchNo, quantityKg, balanceAfterKg,
                closedEventIds, version, replay);
    }
}
