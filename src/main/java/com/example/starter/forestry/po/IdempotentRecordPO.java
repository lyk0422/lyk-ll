package com.example.starter.forestry.po;

import java.time.LocalDate;

/**
 * 幂等请求记录 PO。
 */
public record IdempotentRecordPO(
        String operation,
        String requestId,
        String fingerprint,
        String status,
        String responseType,
        Integer responseStatus,
        String refId,
        String responseJson,
        LocalDate createdDate
) {
}
