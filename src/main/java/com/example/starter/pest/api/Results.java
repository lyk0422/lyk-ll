package com.example.starter.pest.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 写操作响应 DTO；幂等重放时返回首次结果快照。
 */
public final class Results {

    private Results() {
    }

    /**
     * 虫情上报结果。
     */
    public record ReportResult(long id, String zoneCode, String status, Instant reportedAt) {
    }

    /**
     * 虫情确认结果；evaluationId 非空表示恰第 2 次相邻虫情触发了整体预防性消杀评估。
     */
    public record ConfirmResult(long reportId, long chainId, String zoneCode, String zoneStatus,
                                Long evaluationId) {
    }

    /**
     * 出库登记结果。
     */
    public record OutboundResult(long id, String zoneCode, String item, Instant createdAt) {
    }

    /**
     * 治理投放结果；purchaseSuggestionId 非空表示库存低于安全线已生成采购建议。
     */
    public record DispenseResult(long dispenseId, long chainId, long pesticideId,
                                 BigDecimal remainingQuantity, Long purchaseSuggestionId) {
    }

    /**
     * 虫迹登记结果；monitoringStart 为重新起算的监测期起点。
     */
    public record TraceResult(long traceId, long chainId, Instant monitoringStart) {
    }

    /**
     * 解除申请结果。
     */
    public record LiftApplyResult(long chainId, String status, Instant liftRequestedAt) {
    }

    /**
     * 解除确认结果；lifted 为 true 表示消杀与库房双方均已确认、处置链解除。
     */
    public record LiftConfirmResult(long chainId, String status, boolean lifted) {
    }

    /**
     * 处置链查询视图。
     */
    public record ChainView(long id, String zoneCode, long reportId, String status,
                            Instant monitoringStart, Instant liftRequestedAt,
                            boolean confirmDisinfection, boolean confirmWarehouse,
                            Instant liftedAt) {
    }
}
