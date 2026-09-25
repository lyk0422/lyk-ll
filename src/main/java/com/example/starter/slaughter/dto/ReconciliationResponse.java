package com.example.starter.slaughter.dto;

import java.util.List;
import java.util.Map;

/**
 * 批次台账复算结果：由头只台账、判定明细、回执与库存标识实时汇总，可独立核验勾稽关系。
 *
 * @param status                 批次当前状态
 * @param headCount              申报头只总数
 * @param inspectedCount         已同步检验头只数
 * @param missingHeads           漏检头只编号清单
 * @param categoryCounts         各判定类别头只数：QUALIFIED/CONDITIONAL/RENDERED/DESTROYED
 * @param inventoryMarkCount     库存标识条数（应等于合格 + 有条件食用头只数）
 * @param receipts               回执勾稽明细
 * @param unclosedDisposalHeads  化制/销毁但尚无受理回执闭环的头只编号
 * @param lateEventCount         核销终态后迟到回执事件数
 * @param consistent             全部勾稽关系是否一致
 */
public record ReconciliationResponse(
        String batchNo,
        String status,
        int headCount,
        int inspectedCount,
        List<String> missingHeads,
        Map<String, Integer> categoryCounts,
        int inventoryMarkCount,
        List<ReceiptView> receipts,
        List<String> unclosedDisposalHeads,
        int lateEventCount,
        boolean consistent) {

    /**
     * 单张回执的勾稽视图。
     *
     * @param accepted 恰等受理；false 表示不等并拒绝出厂
     * @param late     核销后迟到回执
     */
    public record ReceiptView(
            String receiptNo,
            String category,
            int headCount,
            boolean accepted,
            boolean late,
            boolean alerted) {
    }
}
