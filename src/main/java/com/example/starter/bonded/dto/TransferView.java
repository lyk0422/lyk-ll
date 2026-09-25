package com.example.starter.bonded.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 转关单视图（查询与各操作响应共用）。
 *
 * @param requestId 幂等请求号
 * @param enterpriseId 企业编号
 * @param fromWarehouse 起运仓编号
 * @param toWarehouse 目的仓编号
 * @param status 单据状态
 * @param endorseTime 核注时间（UTC），未核注为 null
 * @param deadline 核销截止时间（UTC，核注后 48 小时），未核注为 null
 * @param enterpriseFrozen 所属企业是否处于异常冻结中
 * @param items 商品项及累计核销量
 * @param history 状态变更留痕（按时间升序）
 */
public record TransferView(
        String requestId,
        String enterpriseId,
        String fromWarehouse,
        String toWarehouse,
        String status,
        Instant endorseTime,
        Instant deadline,
        boolean enterpriseFrozen,
        java.util.List<ItemView> items,
        java.util.List<HistoryView> history) {

    /**
     * 商品项视图。
     */
    public record ItemView(String hsCode, Long quantity, BigDecimal unitPrice, Long verifiedQty) {
    }

    /**
     * 状态变更留痕视图。
     */
    public record HistoryView(String fromStatus, String toStatus, Instant changedAt, String note) {
    }
}
