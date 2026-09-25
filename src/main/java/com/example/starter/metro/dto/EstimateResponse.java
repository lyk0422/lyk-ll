package com.example.starter.metro.dto;

/**
 * 暂记响应。
 *
 * @param txnNo           交易流水号
 * @param status          交易状态（ESTIMATED）
 * @param fareCents       暂记金额（分），按最远站票价
 * @param bizDate         入账业务日期
 * @param lateDiscrepancy 是否因批次已封账而登记对账差异
 * @param replayed        是否幂等重放命中首次结果
 */
public record EstimateResponse(String txnNo, String status, long fareCents,
                               String bizDate, boolean lateDiscrepancy, boolean replayed)
        implements ReplayableResponse<EstimateResponse> {

    @Override
    public EstimateResponse asReplayed() {
        return new EstimateResponse(txnNo, status, fareCents, bizDate, lateDiscrepancy, true);
    }
}
