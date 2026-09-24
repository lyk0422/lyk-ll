package com.example.starter.unblinding;

import java.time.Instant;

/**
 * 揭盲结果。repeated=true 表示重复揭盲请求，返回首次揭盲结果且未新增事件。
 */
public record UnblindResponse(
        String screeningNo,
        String revealedArm,
        String approver1,
        String approver2,
        String reason,
        Instant effectiveAt,
        boolean repeated) {
}
