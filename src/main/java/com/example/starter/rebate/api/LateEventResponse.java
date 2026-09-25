package com.example.starter.rebate.api;

import java.time.Instant;

/**
 * 结案后迟到单证事件。
 *
 * @param id            事件 ID
 * @param batchId       已结案批次 ID
 * @param declarationNo 迟到报关单号
 * @param invoiceNo     迟到发票号
 * @param payload       原始报文
 * @param createdAt     登记时间（UTC）
 * @param replayed      是否幂等重放命中
 */
public record LateEventResponse(
        Long id,
        Long batchId,
        String declarationNo,
        String invoiceNo,
        String payload,
        Instant createdAt,
        boolean replayed
) implements ReplayableResponse<LateEventResponse> {

    @Override
    public LateEventResponse withReplayed(boolean replayed) {
        return new LateEventResponse(id, batchId, declarationNo, invoiceNo, payload,
                createdAt, replayed);
    }
}
