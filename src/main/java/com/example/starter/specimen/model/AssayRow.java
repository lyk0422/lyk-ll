package com.example.starter.specimen.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 检测申请行：占用指定分装的体积；result 在未出结果或已取消时为 null。
 */
public record AssayRow(
        String assayId,
        String aliquotId,
        String project,
        BigDecimal reservedVolumeUl,
        AssayStatus status,
        String result,
        String custodian,
        long version,
        Instant lastEventAt,
        Instant createdAt) {
}
