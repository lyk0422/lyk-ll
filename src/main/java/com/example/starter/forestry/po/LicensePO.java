package com.example.starter.forestry.po;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 采伐许可证 PO。
 */
public record LicensePO(
        String id,
        String applicationId,
        String operatorId,
        String subplotId,
        String speciesCode,
        int quotaYear,
        Integer treeCount,
        BigDecimal volume,
        String status,
        LocalDate issueDate,
        LocalDate expireDate,
        LocalDate harvestDate,
        LocalDate voidDate
) {
}
