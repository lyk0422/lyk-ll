package com.example.starter.legality.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 整组校验结论；任一名成员违规则 overallResult=ILLEGAL，且与各成员结果保持一致。
 */
public record CheckResponse(
        String batchId,
        String overallResult,
        boolean sealed,
        Instant finalizedAt,
        List<MemberDutyResponse> results
) {
}
