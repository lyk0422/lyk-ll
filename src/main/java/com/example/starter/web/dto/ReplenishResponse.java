package com.example.starter.web.dto;

import com.example.starter.idempotent.Replayable;

import java.math.BigDecimal;
import java.util.List;

/**
 * 补液入账结果。
 *
 * @param orderId             补液单ID
 * @param stationCode         门站编码
 * @param transportBatchNo    运输批号
 * @param quantityKg          补液数量 kg
 * @param acceptedConcentrationMgM3 验收浓度 mg/m3
 * @param balanceBeforeKg     入账前余额 kg
 * @param balanceAfterKg      入账后余额 kg
 * @param availableKg         入账后可用量 kg
 * @param closedPendingRequests 本次补液原子关闭的待补液事件来源请求号（不会自动重发旧加注命令）
 * @param version             储罐当前版本号
 * @param idempotentReplay    是否同键同参重放
 */
public record ReplenishResponse(
        Long orderId,
        String stationCode,
        String transportBatchNo,
        BigDecimal quantityKg,
        BigDecimal acceptedConcentrationMgM3,
        BigDecimal balanceBeforeKg,
        BigDecimal balanceAfterKg,
        BigDecimal availableKg,
        List<String> closedPendingRequests,
        Integer version,
        boolean idempotentReplay) implements Replayable<ReplenishResponse> {

    @Override
    public ReplenishResponse withReplay(boolean replay) {
        return new ReplenishResponse(orderId, stationCode, transportBatchNo, quantityKg,
                acceptedConcentrationMgM3, balanceBeforeKg, balanceAfterKg, availableKg,
                closedPendingRequests, version, replay);
    }
}
