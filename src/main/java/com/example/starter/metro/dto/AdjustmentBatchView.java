package com.example.starter.metro.dto;

import java.util.List;

/**
 * 调账批次视图：状态、合计、纳入调整单数与逐线路负向明细。
 *
 * @param batchNo         调账批次号
 * @param status          批次状态（OPEN/SEALED）
 * @param totalCents      批次明细实时合计（分，负数或 0）
 * @param adjustmentCount 已纳入调整单数量
 * @param version         批次当前版本
 * @param details         批次负向明细
 */
public record AdjustmentBatchView(String batchNo, String status, long totalCents,
                                  int adjustmentCount, int version, List<LineDetail> details) {

    /** 调账批次负向明细视图。 */
    public record LineDetail(String adjustmentNo, String txnNo, String lineCode, long amountCents) {
    }
}
