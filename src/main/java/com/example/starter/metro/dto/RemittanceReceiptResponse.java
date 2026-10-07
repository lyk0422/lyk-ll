package com.example.starter.metro.dto;

import java.time.Instant;
import java.util.List;

/**
 * 缴回收款凭据响应。
 *
 * @param statementNo     对账单号
 * @param batchNo         来源调账批次号
 * @param bankFlowNo      合成银行流水号
 * @param arrivedAt       UTC 到账时刻
 * @param totalCents      本笔入账总额（分）
 * @param allocations     逐线路分配（按线路编码升序）
 * @param paidTotalCents  入账后对账单已缴总额（分）
 * @param balanceCents    入账后对账单未缴余额（分）
 * @param statementStatus 入账后对账单状态（OPEN / COMPLETED）
 * @param version         入账后对账单版本
 * @param replayed        是否幂等重放命中首次结果
 */
public record RemittanceReceiptResponse(String statementNo, String batchNo, String bankFlowNo,
                                        Instant arrivedAt, long totalCents,
                                        List<LineAllocation> allocations,
                                        long paidTotalCents, long balanceCents,
                                        String statementStatus, int version, boolean replayed)
        implements ReplayableResponse<RemittanceReceiptResponse> {

    /**
     * 单条线路分配。
     *
     * @param lineCode    线路编码
     * @param amountCents 分配金额（分，正数）
     */
    public record LineAllocation(String lineCode, long amountCents) {
    }

    @Override
    public RemittanceReceiptResponse asReplayed() {
        return new RemittanceReceiptResponse(statementNo, batchNo, bankFlowNo, arrivedAt, totalCents,
                allocations, paidTotalCents, balanceCents, statementStatus, version, true);
    }
}
