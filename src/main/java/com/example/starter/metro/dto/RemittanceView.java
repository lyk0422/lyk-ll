package com.example.starter.metro.dto;

import java.time.Instant;
import java.util.List;

/**
 * 缴回对账单视图：应缴、已缴、余额及合成银行流水分配链。
 *
 * @param statementNo    缴回对账单号
 * @param batchNo        来源调账批次号
 * @param status         对账单状态：OPEN / COMPLETED
 * @param version        对账单当前版本
 * @param totalDueCents  应缴总额（分），可从冻结来源明细复算
 * @param totalPaidCents 已缴总额（分）
 * @param balanceCents   未缴余额（分）
 * @param lines          逐线路应缴/已缴/余额
 * @param receipts       已到账银行流水及其逐线路分配链，按到账登记顺序
 */
public record RemittanceView(String statementNo, String batchNo, String status, int version,
                             long totalDueCents, long totalPaidCents, long balanceCents,
                             List<LineStatus> lines, List<ReceiptChain> receipts) {

    /** 逐线路缴回状态。 */
    public record LineStatus(String lineCode, long dueCents, long paidCents, long balanceCents) {
    }

    /** 单笔合成银行流水及其分配。 */
    public record ReceiptChain(String bankSerialNo, Instant arrivalTimeUtc, long totalCents,
                               List<ChainAllocation> allocations) {
    }

    /** 流水内逐线路分配。 */
    public record ChainAllocation(String lineCode, long amountCents) {
    }
}
