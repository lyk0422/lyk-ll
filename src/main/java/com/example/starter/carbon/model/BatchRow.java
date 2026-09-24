package com.example.starter.carbon.model;

import java.time.LocalDate;

/**
 * 配额/抵消信用批次行（两种批次表结构一致，共用该行模型）。
 */
public record BatchRow(
        long id,
        long enterpriseId,
        int complianceYear,
        long quantityTotal,
        long quantityRemaining,
        LocalDate expiryDate) {
}
