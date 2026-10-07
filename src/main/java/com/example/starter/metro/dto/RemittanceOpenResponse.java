package com.example.starter.metro.dto;

import java.util.List;

/**
 * 缴回对账单建立响应：建立时冻结来源负向明细并生成逐线路应缴额（正数分）。
 *
 * @param statementNo   缴回对账单号
 * @param batchNo       来源调账批次号
 * @param status        对账单状态：OPEN 未缴齐 / COMPLETED 全部线路恰缴齐
 * @param totalDueCents 应缴总额（分，正数），取本批负向调整金额绝对值之和
 * @param version       对账单版本，建立为 1，每笔成功回执加 1
 * @param lines         逐线路应缴额
 * @param replayed      是否幂等重放命中首次结果
 */
public record RemittanceOpenResponse(String statementNo, String batchNo, String status,
                                     long totalDueCents, int version, List<LineDue> lines,
                                     boolean replayed)
        implements ReplayableResponse<RemittanceOpenResponse> {

    /** 逐线路应缴视图（金额为正）。 */
    public record LineDue(String lineCode, long dueCents) {
    }

    @Override
    public RemittanceOpenResponse asReplayed() {
        return new RemittanceOpenResponse(statementNo, batchNo, status, totalDueCents, version,
                lines, true);
    }
}
