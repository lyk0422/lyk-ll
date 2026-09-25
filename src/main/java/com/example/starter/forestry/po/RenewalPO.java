package com.example.starter.forestry.po;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 迹地更新义务 PO。
 */
public record RenewalPO(
        String id,
        String applicationId,
        String operatorId,
        String status,
        LocalDate deadline,
        LocalDate replantDeadline,
        BigDecimal qualifiedRate,
        LocalDate acceptedDate,
        LocalDate replantAcceptedDate,
        LocalDate createdDate
) {
}
