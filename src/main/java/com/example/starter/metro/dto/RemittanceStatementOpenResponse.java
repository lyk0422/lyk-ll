package com.example.starter.metro.dto;

/**
 * 缴回对账单建立响应。
 *
 * @param statementNo   对账单号
 * @param batchNo       来源调账批次号
 * @param status        对账单状态（OPEN）
 * @param dueTotalCents 应缴总额（分）：本批负向调整金额绝对值之和
 * @param lineCount     涉及线路数
 * @param version       建立后版本（1）
 * @param replayed      是否幂等重放命中首次结果
 */
public record RemittanceStatementOpenResponse(String statementNo, String batchNo, String status,
                                              long dueTotalCents, int lineCount, int version,
                                              boolean replayed)
        implements ReplayableResponse<RemittanceStatementOpenResponse> {

    @Override
    public RemittanceStatementOpenResponse asReplayed() {
        return new RemittanceStatementOpenResponse(statementNo, batchNo, status, dueTotalCents,
                lineCount, version, true);
    }
}
