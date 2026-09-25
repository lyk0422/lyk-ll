package com.example.starter.slaughter.dto;

import java.time.OffsetDateTime;

/**
 * 宰前申报响应。
 *
 * @param batchNo       批次编号
 * @param status        批次状态
 * @param headCount     申报头只数
 * @param declaredAtUtc 申报时刻（UTC）
 * @param validUntilUtc 申报有效期截止（UTC，申报时刻 +6 小时，恰满失效）
 * @param redeclareNo   重新申报次数，首次申报为 0
 */
public record DeclareResponse(
        String batchNo,
        String status,
        int headCount,
        OffsetDateTime declaredAtUtc,
        OffsetDateTime validUntilUtc,
        int redeclareNo) {
}
