package com.example.starter.metro.dto;

import java.time.Instant;
import java.util.List;

/**
 * 缴回对账单查询视图：应缴、已缴、余额及流水分配链。
 *
 * @param statementNo    对账单号
 * @param batchNo        来源调账批次号
 * @param status         状态（OPEN / COMPLETED）
 * @param dueTotalCents  应缴总额（分）
 * @param paidTotalCents 已缴总额（分）
 * @param balanceCents   未缴余额（分）
 * @param version        当前版本
 * @param lines          逐线路应缴/已缴/余额
 * @param receipts       已入账回执及逐线路分配链（按入账顺序）
 */
public record RemittanceStatementView(String statementNo, String batchNo, String status,
                                      long dueTotalCents, long paidTotalCents, long balanceCents,
                                      int version, List<LineBalance> lines,
                                      List<ReceiptView> receipts) {

    /**
     * 逐线路余额。
     *
     * @param lineCode     线路编码
     * @param dueCents     应缴额（分，冻结快照）
     * @param paidCents    已缴额（分）
     * @param balanceCents 未缴余额（分）
     */
    public record LineBalance(String lineCode, long dueCents, long paidCents, long balanceCents) {
    }

    /**
     * 单笔回执及其分配链。
     *
     * @param bankFlowNo  合成银行流水号
     * @param arrivedAt   UTC 到账时刻
     * @param totalCents  回执总额（分）
     * @param allocations 逐线路分配
     */
    public record ReceiptView(String bankFlowNo, Instant arrivedAt, long totalCents,
                              List<LineAllocation> allocations) {
    }

    /**
     * 单条线路分配。
     *
     * @param lineCode    线路编码
     * @param amountCents 分配金额（分）
     */
    public record LineAllocation(String lineCode, long amountCents) {
    }
}
