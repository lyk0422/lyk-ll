package com.example.starter.metro.dto;

/**
 * 出闸/补登响应。
 *
 * @param txnNo           交易流水号
 * @param status          交易状态（COMPLETED）
 * @param distanceM       计费里程（米）
 * @param fareCents       实收金额（分）
 * @param penaltyCents    超时补收（分）
 * @param transfer        是否换乘接续
 * @param compensated     是否补登（冲销暂记后按实结算）
 * @param bizDate         入账业务日期
 * @param lateDiscrepancy 是否因批次已封账而登记对账差异
 * @param replayed        是否幂等重放命中首次结果
 */
public record ExitResponse(String txnNo, String status, long distanceM, long fareCents,
                           long penaltyCents, boolean transfer, boolean compensated,
                           String bizDate, boolean lateDiscrepancy, boolean replayed)
        implements ReplayableResponse<ExitResponse> {

    @Override
    public ExitResponse asReplayed() {
        return new ExitResponse(txnNo, status, distanceM, fareCents, penaltyCents, transfer,
                compensated, bizDate, lateDiscrepancy, true);
    }
}
