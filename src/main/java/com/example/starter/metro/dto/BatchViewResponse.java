package com.example.starter.metro.dto;

import java.util.List;

/**
 * 批次视图：批次状态、合计与可逐笔复算的清分明细、对账差异。
 *
 * @param bizDate       业务日期
 * @param status        批次状态（OPEN/SEALED）
 * @param totalCents    批次合计（分）；未封账时为明细实时合计
 * @param txnCount      批次内交易笔数
 * @param details       清分明细（含暂记、冲销、实结）
 * @param discrepancies 对账差异登记
 */
public record BatchViewResponse(String bizDate, String status, long totalCents, int txnCount,
                                List<DetailView> details, List<DiscrepancyView> discrepancies) {

    /**
     * 清分明细视图。
     */
    public record DetailView(String txnNo, String lineCode, long distanceM, String ratio,
                             long amountCents, String entryType) {
    }

    /**
     * 对账差异视图。
     */
    public record DiscrepancyView(String txnNo, long amountCents, String reason) {
    }
}
