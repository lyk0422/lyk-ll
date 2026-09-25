package com.example.starter.slaughter.dto;

import java.time.OffsetDateTime;

/**
 * 宰前检疫登记响应。
 *
 * @param batchNo          批次编号
 * @param status           批次状态
 * @param result           检疫结论
 * @param quarantinedAtUtc 检疫时刻（UTC）
 * @param validUntilUtc    有效期截止（UTC，锚定申报时刻 +6 小时）；FAIL 时为空
 */
public record PreQuarantineResponse(
        String batchNo,
        String status,
        String result,
        OffsetDateTime quarantinedAtUtc,
        OffsetDateTime validUntilUtc) {
}
