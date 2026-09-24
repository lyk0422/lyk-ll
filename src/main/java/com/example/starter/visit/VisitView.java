package com.example.starter.visit;

import java.time.Instant;

/**
 * 访视数据视图。excludedFromPas=true 表示已排除出主要分析集。
 */
public record VisitView(
        long id,
        String screeningNo,
        Instant visitTime,
        String note,
        boolean excludedFromPas) {
}
