package com.example.rebate.web.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 关联校验/批次视图。
 */
public record CheckResponse(
        String batchNo,
        String status,
        String declareDate,
        BigDecimal totalDeclaredTax,
        List<DetailView> details,
        List<DoubtView> doubts) {

    /**
     * 校验明细视图。
     */
    public record DetailView(
            Long detailId,
            String declarationNo,
            String invoiceNo,
            String productCode,
            String versionNo,
            BigDecimal rebateRate,
            BigDecimal taxBasis,
            BigDecimal exchangeCost,
            String result,
            String doubtType) {
    }

    /**
     * 疑点视图。
     */
    public record DoubtView(
            Long doubtId,
            Long detailId,
            String doubtType,
            String status,
            String explanation) {
    }
}
