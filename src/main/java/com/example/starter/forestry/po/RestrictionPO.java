package com.example.starter.forestry.po;

import java.time.LocalDate;

/**
 * 限批名单 PO。
 */
public record RestrictionPO(
        Long id,
        String operatorId,
        String reason,
        LocalDate startDate,
        LocalDate endDate,
        boolean active
) {
}
