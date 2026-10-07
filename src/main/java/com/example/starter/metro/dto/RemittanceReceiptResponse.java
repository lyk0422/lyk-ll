package com.example.starter.metro.dto;

import java.time.Instant;
import java.util.List;

/**
 * 缴回回执响应（合成银行收款凭据）：同流水同完整内容重放首次冻结结果，
 * 重放可能绑定新的 requestId。
 *
 * @param statementNo    缴回对账单号
 * @param bankSerialNo   合成银行流水号
 * @param arrivalTimeUtc UTC 到账时刻
 * @param totalCents     到账总额（分，正数）
 * @param status         入账后对账单状态：OPEN 未缴齐 / COMPLETED 已全部缴齐
 * @param version        入账后对账单版本（重放不递增）
 * @param allocations    逐线路分配（正数分，合计等于总额）
 * @param replayed       是否幂等重放命中首次结果
 */
public record RemittanceReceiptResponse(String statementNo, String bankSerialNo,
                                        Instant arrivalTimeUtc, long totalCents, String status,
                                        int version, List<ReceiptAllocation> allocations,
                                        boolean replayed)
        implements ReplayableResponse<RemittanceReceiptResponse> {

    /** 回执逐线路分配视图。 */
    public record ReceiptAllocation(String lineCode, long amountCents) {
    }

    @Override
    public RemittanceReceiptResponse asReplayed() {
        return new RemittanceReceiptResponse(statementNo, bankSerialNo, arrivalTimeUtc, totalCents,
                status, version, allocations, true);
    }
}
