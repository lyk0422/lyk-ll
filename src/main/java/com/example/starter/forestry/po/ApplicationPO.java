package com.example.starter.forestry.po;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 采伐申请单 PO。
 */
public record ApplicationPO(
        String id,
        String requestId,
        String operatorId,
        String subplotId,
        String speciesCode,
        int quotaYear,
        String harvestType,
        BigDecimal volume,
        String status,
        String licenseId,
        String rejectReason,
        boolean archived,
        LocalDate createdDate,
        LocalDate reviewedDate,
        LocalDate archivedDate
) {
}
