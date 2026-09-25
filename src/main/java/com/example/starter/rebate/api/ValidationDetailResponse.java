package com.example.starter.rebate.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 关联校验明细（可复算）。
 *
 * @param docId              单证 ID
 * @param hsCode             商品编码
 * @param rateEffectiveDate 采用文库版本生效日（UTC）
 * @param rate               采用退税率
 * @param taxBase            计税依据=FOB价×退税率（元）
 * @param cost               换汇成本=计税依据÷FOB美元价
 * @param result             PASS / DOUBT
 */
public record ValidationDetailResponse(
        Long docId,
        String hsCode,
        LocalDate rateEffectiveDate,
        BigDecimal rate,
        BigDecimal taxBase,
        BigDecimal cost,
        String result
) {
}
