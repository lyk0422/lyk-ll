package com.example.starter.web.dto;

import com.example.starter.idempotent.Replayable;

import java.math.BigDecimal;

/**
 * 加注计划回执/取消处理结果。
 *
 * @param planId           计划ID
 * @param planRequestId    计划单号
 * @param status           处理后状态：EXECUTED 已回执 / CANCELLED 已取消
 * @param executedKg       实际消耗 kg（取消时为 null）
 * @param releasedKg       释放回可用量的预占 kg（回执未用部分或取消全额）
 * @param balanceKg        处理后余额 kg
 * @param reservedKg       处理后预占中 kg
 * @param version          处理后储罐版本号
 * @param idempotentReplay 是否为同键同参重放
 */
public record PlanActionResponse(
        Long planId,
        String planRequestId,
        String status,
        BigDecimal executedKg,
        BigDecimal releasedKg,
        BigDecimal balanceKg,
        BigDecimal reservedKg,
        long version,
        boolean idempotentReplay) implements Replayable<PlanActionResponse> {

    @Override
    public PlanActionResponse withReplay(boolean replay) {
        return new PlanActionResponse(planId, planRequestId, status, executedKg, releasedKg,
                balanceKg, reservedKg, version, replay);
    }
}
