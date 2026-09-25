package com.example.rebate.domain;

import java.math.BigDecimal;

/**
 * 关联校验明细，可按相同输入复算。
 *
 * @param taxBasis 计税依据=FOB 价×退税率
 * @param exchangeCost 换汇成本=计税依据÷FOB 美元价，合法区间 [5.0, 8.0]（含端点）
 * @param result PASS / DOUBT
 * @param doubtType 疑点类型
 */
public record CheckDetail(
        Long id,
        Long batchId,
        String declarationNo,
        String invoiceNo,
        String productCode,
        Long libraryVersionId,
        String versionNo,
        BigDecimal fobAmount,
        BigDecimal fobUsd,
        BigDecimal rebateRate,
        BigDecimal taxBasis,
        BigDecimal exchangeCost,
        CheckResult result,
        String doubtType) {
}
