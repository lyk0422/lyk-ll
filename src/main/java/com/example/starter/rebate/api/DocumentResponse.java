package com.example.starter.rebate.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 单证采集结果。
 *
 * @param id            单证 ID
 * @param declarationNo 报关单号
 * @param invoiceNo     发票号
 * @param hsCode        商品编码
 * @param fobRmb        FOB 价（人民币元）
 * @param fobUsd        FOB 美元价（美元）
 * @param status        单证状态
 * @param batchId       所属批次 ID，未关联时为空
 * @param collectedAt   采集时间（UTC）
 * @param replayed      是否为重复采集/幂等重放命中既有记录
 */
public record DocumentResponse(
        Long id,
        String declarationNo,
        String invoiceNo,
        String hsCode,
        BigDecimal fobRmb,
        BigDecimal fobUsd,
        String status,
        Long batchId,
        Instant collectedAt,
        boolean replayed
) implements ReplayableResponse<DocumentResponse> {

    @Override
    public DocumentResponse withReplayed(boolean replayed) {
        return new DocumentResponse(id, declarationNo, invoiceNo, hsCode, fobRmb, fobUsd,
                status, batchId, collectedAt, replayed);
    }
}
