package com.example.starter.model;

import java.time.Instant;

/**
 * 公示记录行。
 *
 * @param processId           关联流程ID
 * @param startAt             公示开始时间（UTC）
 * @param endAt               公示期满时间（UTC，开始后7个自然日）
 * @param status              公示状态：IN_PROGRESS/OBJECTION/EFFECTIVE
 * @param objectionRequestId  异议受理请求ID，无异议为null
 * @param objectionReason     异议理由，无异议为null
 */
public record PublicityRow(
        long processId,
        Instant startAt,
        Instant endAt,
        String status,
        String objectionRequestId,
        String objectionReason) {
}
