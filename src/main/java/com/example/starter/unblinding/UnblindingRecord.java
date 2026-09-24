package com.example.starter.unblinding;

import java.time.Instant;

/**
 * 揭盲记录（终态，不可回退）。
 */
public record UnblindingRecord(
        long id,
        long trialId,
        String screeningNo,
        String approver1,
        String approver2,
        String reason,
        char revealedArm,
        Instant effectiveAt) {
}
